package com.wcdk.r2dbc.core.transaction;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.IsolationLevel;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/**
 * 创建活动的手动事务。调用方负责管理并关闭返回的事务。
 * @author wcdk
 */
public class TransactionManager {

    private final ConnectionFactory connectionFactory;

    public TransactionManager(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public Mono<ManualTransaction> createTransaction() {
        return createTransaction("manual-" + System.currentTimeMillis());
    }

    public Mono<ManualTransaction> createTransaction(String transactionName) {
        return createTransaction(transaction -> transaction.setName(
                transactionName == null || transactionName.isBlank()
                        ? "manual-" + System.currentTimeMillis()
                        : transactionName));
    }

    public Mono<ManualTransaction> createReadOnlyTransaction() {
        return createTransaction(transaction -> transaction.setReadOnly(true));
    }

    public Mono<ManualTransaction> createTransaction(int timeoutSeconds) {
        return createTransaction(transaction -> transaction.setTimeout(timeoutSeconds));
    }

    public Mono<ManualTransaction> createTransaction(IsolationLevel isolationLevel) {
        return createTransaction(transaction -> transaction.setIsolationLevel(isolationLevel));
    }

    private Mono<ManualTransaction> createTransaction(Consumer<ManualTransactionImpl> configure) {
        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
                    try {
                        configure.accept(transaction);
                    } catch (Throwable error) {
                        return closeAfterAcquireFailure(connection, error);
                    }
                    return transaction.activate()
                            .thenReturn((ManualTransaction) transaction)
                            .onErrorResume(error -> closeAfterAcquireFailure(connection, error));
                });
    }

    private Mono<ManualTransaction> closeAfterAcquireFailure(Connection connection, Throwable error) {
        return Mono.from(connection.close())
                .onErrorResume(closeError -> {
                    error.addSuppressed(closeError);
                    return Mono.empty();
                })
                .then(Mono.error(error));
    }

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }
}