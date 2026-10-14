package com.wcdk.r2dbc.transaction;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.TransactionDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManualTransactionTests {

    @Test
    void commitsAndClosesExactlyOnce() {
        Connection connection = connection();
        ManualTransactionImpl transaction = new ManualTransactionImpl(connection);

        StepVerifier.create(transaction.activate().then(transaction.commit()))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(transaction.close().then(transaction.close())).verifyComplete();

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.COMMITTED);
        InOrder order = inOrder(connection);
        order.verify(connection).beginTransaction(any(TransactionDefinition.class));
        order.verify(connection).commitTransaction();
        order.verify(connection).close();
        verify(connection, never()).rollbackTransaction();
        verify(connection, times(1)).close();
    }

    @Test
    void rejectsRepeatedOrInvalidTerminalTransitions() {
        Connection connection = connection();
        ManualTransactionImpl transaction = new ManualTransactionImpl(connection);

        StepVerifier.create(transaction.activate().then(transaction.rollback()))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(transaction.rollback())
                .expectError(IllegalStateException.class)
                .verify();
        StepVerifier.create(transaction.commit())
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void passesReadOnlyTimeoutAndIsolationToBeginDefinition() {
        Connection connection = connection();
        ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
        transaction.setName("configured");
        transaction.setReadOnly(true);
        transaction.setTimeout(7);
        transaction.setIsolationLevel(IsolationLevel.SERIALIZABLE);

        StepVerifier.create(transaction.activate()).verifyComplete();

        ArgumentCaptor<TransactionDefinition> captor = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(connection).beginTransaction(captor.capture());
        TransactionDefinition definition = captor.getValue();
        assertThat(definition.getAttribute(TransactionDefinition.NAME)).isEqualTo("configured");
        assertThat(definition.getAttribute(TransactionDefinition.READ_ONLY)).isEqualTo(true);
        assertThat(definition.getAttribute(TransactionDefinition.LOCK_WAIT_TIMEOUT)).isEqualTo(Duration.ofSeconds(7));
        assertThat(definition.getAttribute(TransactionDefinition.ISOLATION_LEVEL)).isEqualTo(IsolationLevel.SERIALIZABLE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.setReadOnly(false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsRollsBackAndReleasesSavepointsSafely() {
        Connection connection = connection();
        when(connection.createSavepoint(any(String.class))).thenReturn(Mono.empty());
        when(connection.rollbackTransactionToSavepoint(any(String.class))).thenReturn(Mono.empty());
        when(connection.releaseSavepoint(any(String.class))).thenReturn(Mono.empty());
        ManualTransactionImpl transaction = new ManualTransactionImpl(connection);

        StepVerifier.create(transaction.activate()
                        .then(transaction.createSavepoint("first"))
                        .flatMap(first -> transaction.createSavepoint("second")
                                .flatMap(second -> transaction.rollbackToSavepoint(first)
                                        .thenReturn(new Savepoint[]{first, second}))))
                .assertNext(points -> {
                    assertThat(points[0].isValid()).isTrue();
                    assertThat(points[1].isValid()).isFalse();
                })
                .verifyComplete();

        StepVerifier.create(transaction.createSavepoint("bad-name"))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(transaction.createSavepoint("release_me")
                        .flatMap(point -> transaction.releaseSavepoint(point).thenReturn(point)))
                .assertNext(point -> assertThat(point.isValid()).isFalse())
                .verifyComplete();

        // 验证保存点操作全部通过 R2DBC SPI 执行，不拼接 SQL。
        verify(connection).createSavepoint("first");
        verify(connection).createSavepoint("second");
        verify(connection).rollbackTransactionToSavepoint("first");
        verify(connection).createSavepoint("release_me");
        verify(connection).releaseSavepoint("release_me");
    }
    @Test
    void cancelledCommitWaitsForTerminalBeforeCloseWithoutRollback() {
        Connection connection = connection();
        var commit = reactor.core.publisher.Sinks.<Void>empty();
        when(connection.commitTransaction()).thenReturn(commit.asMono());
        ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
        transaction.activate().block();
        StepVerifier.create(transaction.commit())
                .then(() -> assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.COMMITTING))
                .thenCancel().verify();
        StepVerifier.create(transaction.close())
                .then(() -> {
                    verify(connection, never()).rollbackTransaction();
                    verify(connection, never()).close();
                    commit.tryEmitEmpty();
                }).verifyComplete();
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.COMMITTED);
        verify(connection).close();
        StepVerifier.create(transaction.commit()).expectError(IllegalStateException.class).verify();
    }

    @Test
    void failedCommitCloseTriesRollbackEvenIfRollbackFails() {
        Connection connection = connection();
        when(connection.commitTransaction()).thenReturn(Mono.error(new IllegalStateException("commit")));
        when(connection.rollbackTransaction()).thenReturn(Mono.error(new IllegalStateException("rollback")));
        ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
        transaction.activate().block();
        StepVerifier.create(transaction.commit()).expectErrorMessage("commit").verify();
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
        StepVerifier.create(transaction.close()).verifyComplete();
        InOrder order = inOrder(connection);
        order.verify(connection).beginTransaction(any(TransactionDefinition.class));
        order.verify(connection).commitTransaction();
        order.verify(connection).rollbackTransaction();
        order.verify(connection).close();
    }

    @Test
    void rejectsCommitAndRollbackForEveryNonActiveState() {
        for (TransactionStatus state : TransactionStatus.values()) {
            if (state == TransactionStatus.ACTIVE || state == TransactionStatus.FAILED) continue;
            ManualTransactionImpl transaction = new ManualTransactionImpl(connection());
            transaction.setStatus(state);
            StepVerifier.create(transaction.commit()).expectError(IllegalStateException.class).verify();
            StepVerifier.create(transaction.rollback()).expectError(IllegalStateException.class).verify();
            assertThat(transaction.getStatus()).isEqualTo(state);
        }
    }

    @Test
    void duplicateSavepointIsRejectedWhileDriverCreationIsInFlight() {
        Connection connection = connection();
        var created = reactor.core.publisher.Sinks.<Void>empty();
        when(connection.createSavepoint("same")).thenReturn(created.asMono());
        ManualTransactionImpl transaction = new ManualTransactionImpl(connection);
        transaction.activate().block();
        StepVerifier.create(transaction.createSavepoint("same"))
                .then(() -> {
                    StepVerifier.create(transaction.createSavepoint("same"))
                            .expectErrorMatches(error -> error.getMessage().contains("same")).verify();
                    created.tryEmitEmpty();
                }).assertNext(point -> assertThat(point.isValid()).isTrue()).verifyComplete();
        verify(connection, times(1)).createSavepoint("same");
    }

    @Test
    void timeoutDocumentationMatchesDriverLockWaitDefinition() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/wcdk/r2dbc/transaction/ManualTransaction.java"));
        assertThat(source).contains("LOCK_WAIT_TIMEOUT", "不提供事务执行超时或自动回滚");
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