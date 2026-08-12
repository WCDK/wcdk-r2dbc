package com.wcdk.r2dbc.transaction;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.TransactionDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionManagerTests {

    @Test
    void keepsConnectionOpenUntilCallerClosesTransaction() {
        Connection connection = connection();
        TransactionManager manager = manager(connection);

        ManualTransaction transaction = manager.createTransaction("test").block();

        assertThat(transaction).isNotNull();
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.ACTIVE);
        verify(connection, never()).close();

        StepVerifier.create(transaction.close()).verifyComplete();
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.ROLLED_BACK);
        verify(connection).rollbackTransaction();
        verify(connection).close();
    }

    @Test
    void closesConnectionWhenBeginFails() {
        Connection connection = connection();
        IllegalStateException beginError = new IllegalStateException("begin");
        when(connection.beginTransaction(any(TransactionDefinition.class))).thenReturn(Mono.error(beginError));

        StepVerifier.create(manager(connection).createTransaction())
                .expectErrorMatches(error -> error == beginError)
                .verify();

        verify(connection).close();
        verify(connection, never()).commitTransaction();
    }

    @Test
    void closesConnectionWhenConfigurationFails() {
        Connection connection = connection();

        StepVerifier.create(manager(connection).createTransaction(-1))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(connection).close();
        verify(connection, never()).beginTransaction(any(TransactionDefinition.class));
    }

    private TransactionManager manager(Connection connection) {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        doReturn(Mono.just(connection)).when(factory).create();
        return new TransactionManager(factory);
    }

    private Connection connection() {
        Connection connection = mock(Connection.class);
        when(connection.beginTransaction(any(TransactionDefinition.class))).thenReturn(Mono.empty());
        when(connection.commitTransaction()).thenReturn(Mono.empty());
        when(connection.rollbackTransaction()).thenReturn(Mono.empty());
        when(connection.close()).thenReturn(Mono.empty());
        return connection;
    }
}