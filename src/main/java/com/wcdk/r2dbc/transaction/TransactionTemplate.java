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
        return R2dbcDataSourceContext.pinTransactionDataSource(execution, primaryDataSource());
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
                     * 以有界内存流式传递结果，事务提交成功后才发送完成信号。
                     * 订阅者可能在提交前观察到结果值，与标准响应式事务语义一致。
                     */
                    return result.concatWith(transaction.commit().thenMany(Flux.empty()));
                },
                ManualTransaction::close,
                this::rollbackAndClose,
                ManualTransaction::close);
        return R2dbcDataSourceContext.pinTransactionDataSource(execution, primaryDataSource());
    }

    /** 带数据源守卫的手动事务作用域。回调负责提交/回滚，离开作用域仍未完成则回滚并关闭。 */
    public <T> Mono<T> executeManual(Function<ManualTransaction, Mono<T>> action) {
        Objects.requireNonNull(action, "action");
        Mono<T> execution = Mono.usingWhen(transactionManager.createTransaction(),
                transaction -> Mono.defer(() -> action.apply(transaction)),
                ManualTransaction::close, this::rollbackAndClose, ManualTransaction::close);
        return R2dbcDataSourceContext.pinTransactionDataSource(execution, primaryDataSource());
    }

    private String primaryDataSource() {
        return transactionManager.getConnectionFactory() instanceof
                com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory routing ? routing.getPrimary() : null;
    }

    public Mono<ManualTransaction> getTransaction() {
        return transactionManager.createTransaction();
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
