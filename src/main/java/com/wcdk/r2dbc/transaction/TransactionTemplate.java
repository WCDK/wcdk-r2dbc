package com.wcdk.r2dbc.transaction;

import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import io.r2dbc.spi.Connection;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * 执行响应式事务操作，并确保提交、回滚和连接清理具有确定性。
 * @author wcdk
 */
public class TransactionTemplate {

    private final TransactionManager transactionManager;

    public TransactionTemplate(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public <T> Mono<T> execute(Function<Connection, Publisher<T>> action) {
        return execute(null, action);
    }

    public <T> Mono<T> execute(String transactionName, Function<Connection, Publisher<T>> action) {
        return executeMono(transactionManager.createTransaction(transactionName), action);
    }

    public <T> Mono<T> executeReadOnly(Function<Connection, Publisher<T>> action) {
        return executeMono(transactionManager.createReadOnlyTransaction(), action);
    }

    private <T> Mono<T> executeMono(Mono<ManualTransaction> resource,
                                    Function<Connection, Publisher<T>> action) {
        Objects.requireNonNull(action, "action");
        Mono<T> execution = Mono.usingWhen(
                resource,
                transaction -> Mono.defer(() -> Flux.from(action.apply(transaction.getConnection()))
                                .take(2)
                                .collectList())
                        .flatMap(values -> {
                            if (values.size() > 1) {
                                return Mono.error(new IllegalStateException(
                                        "Mono transaction action emitted more than one item; use executeInTransaction"));
                            }
                            return transaction.commit().then(Mono.just(values));
                        })
                        .flatMap(values -> values.isEmpty() ? Mono.empty() : Mono.just(values.getFirst())),
                ManualTransaction::close,
                this::rollbackAndClose,
                ManualTransaction::close);
        return R2dbcDataSourceContext.pinTransactionDataSource(execution);
    }

    @SafeVarargs
    public final <T> Flux<T> executeInTransaction(Function<Connection, Publisher<T>>... actions) {
        Objects.requireNonNull(actions, "actions");
        Flux<T> execution = Flux.usingWhen(
                transactionManager.createTransaction(),
                transaction -> {
                    Connection connection = transaction.getConnection();
                    Flux<T> result = Flux.empty();
                    for (Function<Connection, Publisher<T>> action : actions) {
                        Objects.requireNonNull(action, "action");
                        result = result.concatWith(Flux.defer(() -> Flux.from(action.apply(connection))));
                    }
                    /*
                     * Stream results with bounded memory. Completion is delayed until commit succeeds;
                     * values may be observed before commit, matching standard reactive transaction semantics.
                     */
                    return result.concatWith(transaction.commit().thenMany(Flux.empty()));
                },
                ManualTransaction::close,
                this::rollbackAndClose,
                ManualTransaction::close);
        return R2dbcDataSourceContext.pinTransactionDataSource(execution);
    }

    public Mono<ManualTransaction> getTransaction() {
        return transactionManager.createTransaction();
    }

    public <T> Mono<T> wrapReadOnly(Mono<T> mono) {
        Objects.requireNonNull(mono, "mono");
        return executeReadOnly(connection -> mono);
    }

    public <T> Flux<T> wrapReadOnly(Flux<T> flux) {
        Objects.requireNonNull(flux, "flux");
        Flux<T> execution = Flux.usingWhen(
                transactionManager.createReadOnlyTransaction(),
                transaction -> flux.concatWith(transaction.commit().thenMany(Flux.empty())),
                ManualTransaction::close,
                this::rollbackAndClose,
                ManualTransaction::close);
        return R2dbcDataSourceContext.pinTransactionDataSource(execution);
    }

    private Mono<Void> rollbackAndClose(ManualTransaction transaction, Throwable originalError) {
        Mono<Void> rollback = transaction.isActive() || transaction.getStatus() == TransactionStatus.FAILED
                ? transaction.rollback()
                        .onErrorResume(rollbackError -> {
                            originalError.addSuppressed(rollbackError);
                            return Mono.empty();
                        })
                        .then()
                : Mono.empty();

        return rollback.then(transaction.close()
                        .onErrorResume(closeError -> {
                            originalError.addSuppressed(closeError);
                            return Mono.empty();
                        }))
                .then();
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }
}
