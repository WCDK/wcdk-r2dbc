package com.wcdk.r2dbc.core.transaction;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.ValidationDepth;
import io.r2dbc.spi.TransactionDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionTemplateTests {

    @Test
    void rejectsMultipleItemsFromMonoTransactionApi() {
        Fixture fixture = fixture();

        StepVerifier.create(fixture.template.execute(connection -> Flux.just("one", "two")))
                .expectErrorMessage("Mono transaction action emitted more than one item; use executeInTransaction")
                .verify();

        verify(fixture.connection, never()).commitTransaction();
        verify(fixture.connection).rollbackTransaction();
        verify(fixture.connection).close();
    }

    @Test
    void fluxTransactionStreamsLargeResultsWithoutCollectingThem() {
        Fixture fixture = fixture();

        StepVerifier.create(fixture.template.executeInTransaction(
                        connection -> Flux.range(0, 100_000)))
                .expectNextCount(100_000)
                .verifyComplete();

        verify(fixture.connection).commitTransaction();
        verify(fixture.connection, never()).rollbackTransaction();
        verify(fixture.connection).close();
    }

    @Test
    void commitsSuccessfulWorkThenClosesConnection() {
        Fixture fixture = fixture();

        StepVerifier.create(fixture.template.execute(connection -> Mono.just("ok")))
                .expectNext("ok")
                .verifyComplete();

        InOrder order = inOrder(fixture.connection);
        order.verify(fixture.connection).beginTransaction(any(TransactionDefinition.class));
        order.verify(fixture.connection).commitTransaction();
        order.verify(fixture.connection).close();
        verify(fixture.connection, never()).rollbackTransaction();
    }

    @Test
    void commitsEmptySuccessfulWork() {
        Fixture fixture = fixture();

        StepVerifier.create(fixture.template.execute(connection -> Mono.empty()))
                .verifyComplete();

        verify(fixture.connection).commitTransaction();
        verify(fixture.connection).close();
    }

    @Test
    void rollsBackBusinessFailureAndKeepsRollbackFailureSuppressed() {
        Fixture fixture = fixture();
        IllegalStateException businessError = new IllegalStateException("business");
        IllegalArgumentException rollbackError = new IllegalArgumentException("rollback");
        when(fixture.connection.rollbackTransaction()).thenReturn(Mono.error(rollbackError));

        StepVerifier.create(fixture.template.execute(connection -> Mono.error(businessError)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isSameAs(businessError);
                    assertThat(error.getSuppressed()).containsExactly(rollbackError);
                })
                .verify();

        verify(fixture.connection).rollbackTransaction();
        verify(fixture.connection).close();
    }

    @Test
    void cancellationRollsBackAndClosesConnection() {
        Fixture fixture = fixture();

        StepVerifier.create(fixture.template.execute(connection -> Mono.never()))
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();

        verify(fixture.connection, timeout(1000)).rollbackTransaction();
        verify(fixture.connection, timeout(1000)).close();
    }

    @Test
    void timeoutCancelsWorkThenRollsBackAndClosesConnection() {
        Fixture fixture = fixture();

        StepVerifier.create(fixture.template.execute(connection -> Mono.never())
                        .timeout(Duration.ofMillis(20)))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify();

        verify(fixture.connection, timeout(1000)).rollbackTransaction();
        verify(fixture.connection, timeout(1000)).close();
    }
    @Test
    void closeFailureIsVisibleAfterSuccessfulWork() {
        Fixture fixture = fixture();
        IllegalStateException closeError = new IllegalStateException("close");
        when(fixture.connection.close()).thenReturn(Mono.error(closeError));

        StepVerifier.create(fixture.template.execute(connection -> Mono.just("ok")))
                .expectErrorSatisfies(error -> assertThat(error).hasRootCause(closeError))
                .verify();
    }

    @Test
    void realR2dbcPoolReturnsAcquiredSizeToBaselineUnderLoad() {
        ConnectionFactory delegate = mock(ConnectionFactory.class);
        ConnectionFactoryMetadata metadata = mock(ConnectionFactoryMetadata.class);
        when(metadata.getName()).thenReturn("test");
        when(delegate.getMetadata()).thenReturn(metadata);
        doAnswer(invocation -> Mono.fromSupplier(() -> {
            Connection connection = mock(Connection.class);
            when(connection.beginTransaction(any(TransactionDefinition.class))).thenReturn(Mono.empty());
            when(connection.commitTransaction()).thenReturn(Mono.empty());
            when(connection.rollbackTransaction()).thenReturn(Mono.empty());
            when(connection.close()).thenReturn(Mono.empty());
            when(connection.validate(any(ValidationDepth.class))).thenReturn(Mono.just(true));
            when(connection.setAutoCommit(anyBoolean())).thenReturn(Mono.empty());
            return connection;
        })).when(delegate).create();
        ConnectionPool pool = new ConnectionPool(ConnectionPoolConfiguration.builder(delegate)
                .initialSize(0)
                .maxSize(2)
                .build());
        TransactionTemplate template = new TransactionTemplate(new TransactionManager(pool));

        try {
            StepVerifier.create(reactor.core.publisher.Flux.range(0, 200)
                            .flatMap(index -> template.execute(connection -> Mono.just(index)), 8))
                    .expectNextCount(200)
                    .verifyComplete();

            assertThat(pool.getMetrics().orElseThrow().acquiredSize()).isZero();
        } finally {
            pool.disposeLater().block();
        }
    }
    @Test
    void repeatedTransactionsReturnAcquiredConnectionsToBaseline() {
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        doAnswer(invocation -> Mono.fromSupplier(() -> {
            int current = acquired.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            Connection connection = mock(Connection.class);
            when(connection.beginTransaction(any(TransactionDefinition.class))).thenReturn(Mono.empty());
            when(connection.commitTransaction()).thenReturn(Mono.empty());
            when(connection.rollbackTransaction()).thenReturn(Mono.empty());
            when(connection.close()).thenReturn(Mono.fromRunnable(acquired::decrementAndGet));
            return connection;
        })).when(factory).create();
        TransactionTemplate template = new TransactionTemplate(new TransactionManager(factory));

        StepVerifier.create(reactor.core.publisher.Flux.range(0, 100)
                        .concatMap(index -> template.execute(connection -> Mono.just(index))))
                .expectNextCount(100)
                .verifyComplete();

        assertThat(acquired).hasValue(0);
        assertThat(maximum).hasValue(1);
    }
    private Fixture fixture() {
        Connection connection = mock(Connection.class);
        when(connection.beginTransaction(any(TransactionDefinition.class))).thenReturn(Mono.empty());
        when(connection.commitTransaction()).thenReturn(Mono.empty());
        when(connection.rollbackTransaction()).thenReturn(Mono.empty());
        when(connection.close()).thenReturn(Mono.empty());
        ConnectionFactory factory = mock(ConnectionFactory.class);
        doReturn(Mono.just(connection)).when(factory).create();
        return new Fixture(connection, new TransactionTemplate(new TransactionManager(factory)));
    }

    private record Fixture(Connection connection, TransactionTemplate template) {
    }
}
