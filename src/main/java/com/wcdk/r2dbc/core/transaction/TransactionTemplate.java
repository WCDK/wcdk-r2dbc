package com.wcdk.r2dbc.core.transaction;

import io.r2dbc.spi.Connection;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 事务模板。
 * <p>
 * 提供简洁的事务操作API，支持声明式和编程式事务。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class TransactionTemplate {

    private final TransactionManager transactionManager;

    public TransactionTemplate(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * 执行事务操作（自动提交/回滚）。
     * <p>
     * 如果操作成功，自动提交事务；如果操作失败，自动回滚事务。
     *
     * @param action 事务操作
     * @param <T>    返回类型
     * @return 操作结果
     */
    public <T> Mono<T> execute(Function<Connection, Publisher<T>> action) {
        return execute(null, action);
    }

    /**
     * 执行事务操作（自动提交/回滚）。
     *
     * @param transactionName 事务名称
     * @param action          事务操作
     * @param <T>             返回类型
     * @return 操作结果
     */
    public <T> Mono<T> execute(String transactionName, Function<Connection, Publisher<T>> action) {
        return transactionManager.createTransaction(transactionName)
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    return Mono.from(action.apply(connection))
                            .flatMap(result -> transaction.commit().thenReturn(result))
                            .onErrorResume(error -> transaction.rollback()
                                    .onErrorResume(rollbackError -> {
                                        error.addSuppressed(rollbackError);
                                        return Mono.empty();
                                    })
                                    .then(Mono.error(error)));
                });
    }

    /**
     * 执行只读事务操作。
     * <p>
     * 只读事务不会修改数据库，某些数据库可以对此进行优化。
     *
     * @param action 事务操作
     * @param <T>    返回类型
     * @return 操作结果
     */
    public <T> Mono<T> executeReadOnly(Function<Connection, Publisher<T>> action) {
        return transactionManager.createReadOnlyTransaction()
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    return Mono.from(action.apply(connection))
                            .flatMap(result -> transaction.commit().thenReturn(result))
                            .onErrorResume(error -> transaction.rollback()
                                    .onErrorResume(rollbackError -> {
                                        error.addSuppressed(rollbackError);
                                        return Mono.empty();
                                    })
                                    .then(Mono.error(error)));
                });
    }

    /**
     * 执行多个操作的事务。
     * <p>
     * 将多个操作组合成一个事务执行。
     *
     * @param actions 事务操作列表
     * @param <T>     返回类型
     * @return 操作结果
     */
    @SafeVarargs
    public final <T> Flux<T> executeInTransaction(Function<Connection, Publisher<T>>... actions) {
        return transactionManager.createTransaction()
                .flatMapMany(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    Flux<T> result = Flux.empty();
                    for (Function<Connection, Publisher<T>> action : actions) {
                        result = result.concatWith(Flux.from(action.apply(connection)));
                    }
                    return result
                            .collectList()
                            .flatMapMany(list -> transaction.commit().thenMany(Flux.fromIterable(list)))
                            .onErrorResume(error -> transaction.rollback()
                                    .onErrorResume(rollbackError -> {
                                        error.addSuppressed(rollbackError);
                                        return Mono.empty();
                                    })
                                    .thenMany(Flux.error(error)));
                });
    }

    /**
     * 手动控制事务。
     * <p>
     * 返回ManualTransaction对象，允许手动控制提交和回滚。
     *
     * @return 手动事务
     */
    public Mono<ManualTransaction> getTransaction() {
        return transactionManager.createTransaction();
    }

    /**
     * 包装 Mono 为只读事务。
     *
     * @param mono 原始 Mono
     * @param <T>  返回类型
     * @return 包装后的 Mono
     */
    public <T> Mono<T> wrapReadOnly(Mono<T> mono) {
        return transactionManager.createReadOnlyTransaction()
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    return mono
                            .flatMap(result -> transaction.commit().thenReturn(result))
                            .onErrorResume(error -> transaction.rollback()
                                    .onErrorResume(rollbackError -> {
                                        error.addSuppressed(rollbackError);
                                        return Mono.empty();
                                    })
                                    .then(Mono.error(error)));
                });
    }

    /**
     * 获取事务管理器。
     *
     * @return 事务管理器
     */
    public TransactionManager getTransactionManager() {
        return transactionManager;
    }
}
