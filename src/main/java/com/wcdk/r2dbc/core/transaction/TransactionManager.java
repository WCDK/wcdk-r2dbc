package com.wcdk.r2dbc.core.transaction;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 事务管理器。
 * <p>
 * 负责创建和管理手动事务。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class TransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

    private final ConnectionFactory connectionFactory;

    public TransactionManager(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * 创建新的手动事务。
     *
     * @return 手动事务
     */
    public Mono<ManualTransaction> createTransaction() {
        return createTransaction("manual-" + System.currentTimeMillis());
    }

    /**
     * 创建新的手动事务。
     * <p>
     * 使用 {@code Mono.usingWhen} 管理连接生命周期：
     * <ul>
     *   <li>正常完成后关闭连接</li>
     *   <li>异常完成后回滚并关闭连接</li>
     *   <li>取消订阅时回滚并关闭连接</li>
     *   <li>关闭连接失败时保留原始业务异常，并记录关闭异常</li>
     * </ul>
     *
     * @param transactionName 事务名称
     * @return 手动事务
     */
    public Mono<ManualTransaction> createTransaction(String transactionName) {
        Mono<ManualTransaction> resource = Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
                    transaction.setName(transactionName);
                    return transaction.activate()
                            .then(Mono.just((ManualTransaction) transaction));
                })
                .onErrorResume(e -> {
                    if (e.getCause() != null) {
                        return Mono.error(e.getCause());
                    }
                    return Mono.error(e);
                });

        return Mono.usingWhen(
                resource,
                transaction -> Mono.just(transaction),
                this::cleanupTransaction
        );
    }

    /**
     * 事务清理方法。
     * <p>
     * 如果事务仍处于 ACTIVE 状态，先回滚再关闭连接。
     * 如果关闭连接失败，记录警告日志但不影响原始异常。
     *
     * @param transaction 手动事务
     * @return 清理完成信号
     */
    private Mono<Void> cleanupTransaction(ManualTransaction transaction) {
        ManualTransactionImpl impl = (ManualTransactionImpl) transaction;
        Connection connection = impl.getConnection();
        TransactionStatus status = impl.getStatus();

        Mono<Void> rollbackIfNeeded = (status == TransactionStatus.ACTIVE)
                ? Mono.from(connection.rollbackTransaction())
                        .doOnSuccess(v -> impl.setStatus(TransactionStatus.ROLLED_BACK))
                        .doOnError(e -> log.warn("Failed to rollback transaction [{}] during cleanup", transaction.getName(), e))
                        .then()
                : Mono.empty();

        return rollbackIfNeeded
                .then(Mono.from(connection.close())
                        .doOnError(e -> log.warn("Failed to close connection for transaction [{}]", transaction.getName(), e)))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * 创建只读事务。
     * <p>
     * 注意：readOnly 属性必须在事务开始前设置。
     *
     * @return 只读事务
     */
    public Mono<ManualTransaction> createReadOnlyTransaction() {
        Mono<ManualTransaction> resource = Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
                    transaction.setReadOnly(true);
                    return transaction.activate()
                            .then(Mono.just((ManualTransaction) transaction));
                })
                .onErrorResume(e -> {
                    if (e.getCause() != null) {
                        return Mono.error(e.getCause());
                    }
                    return Mono.error(e);
                });

        return Mono.usingWhen(
                resource,
                transaction -> Mono.just(transaction),
                this::cleanupTransaction
        );
    }

    /**
     * 创建带超时时间的事务。
     * <p>
     * 注意：timeout 属性必须在事务开始前设置。
     *
     * @param timeoutSeconds 超时时间（秒）
     * @return 事务
     */
    public Mono<ManualTransaction> createTransaction(int timeoutSeconds) {
        Mono<ManualTransaction> resource = Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
                    transaction.setTimeout(timeoutSeconds);
                    return transaction.activate()
                            .then(Mono.just((ManualTransaction) transaction));
                })
                .onErrorResume(e -> {
                    if (e.getCause() != null) {
                        return Mono.error(e.getCause());
                    }
                    return Mono.error(e);
                });

        return Mono.usingWhen(
                resource,
                transaction -> Mono.just(transaction),
                this::cleanupTransaction
        );
    }

    /**
     * 获取连接工厂。
     *
     * @return 连接工厂
     */
    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }
}
