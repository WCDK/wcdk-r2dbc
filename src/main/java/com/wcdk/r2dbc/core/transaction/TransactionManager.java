package com.wcdk.r2dbc.core.transaction;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
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
     *
     * @param transactionName 事务名称
     * @return 手动事务
     */
    public Mono<ManualTransaction> createTransaction(String transactionName) {
        return Mono.from(connectionFactory.create())
                .map(connection -> {
                    ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
                    transaction.setName(transactionName);
                    transaction.activate();
                    return transaction;
                });
    }

    /**
     * 创建只读事务。
     *
     * @return 只读事务
     */
    public Mono<ManualTransaction> createReadOnlyTransaction() {
        return createTransaction()
                .doOnSuccess(transaction -> transaction.setReadOnly(true));
    }

    /**
     * 创建带超时时间的事务。
     *
     * @param timeoutSeconds 超时时间（秒）
     * @return 事务
     */
    public Mono<ManualTransaction> createTransaction(int timeoutSeconds) {
        return createTransaction()
                .doOnSuccess(transaction -> transaction.setTimeout(timeoutSeconds));
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
