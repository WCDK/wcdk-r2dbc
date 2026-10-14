package com.wcdk.r2dbc.transaction;

import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceAspect;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DataSourceTransactionOrderingTests {

    @Test
    void dataSourceAspectRunsOutsideTransactionAspect() {
        Order dataSourceOrder = AnnotationUtils.findAnnotation(R2dbcDataSourceAspect.class, Order.class);
        Order transactionOrder = AnnotationUtils.findAnnotation(TransactionalAspect.class, Order.class);

        assertThat(dataSourceOrder).isNotNull();
        assertThat(transactionOrder).isNotNull();
        assertThat(dataSourceOrder.value()).isLessThan(transactionOrder.value());
    }

    @Test
    void rejectsDataSourceSwitchAfterTransactionStarts() {
        Mono<String> switched = R2dbcDataSourceContext.pinTransactionDataSource(
                R2dbcDataSourceContext.use("secondary", Mono.just("value")));

        StepVerifier.create(switched)
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("事务开始后无法将 R2DBC 数据源"))
                .verify();
    }

    @Test
    void allowsNestedUseOfPinnedDataSource() {
        Mono<String> nested = R2dbcDataSourceContext.use("secondary",
                R2dbcDataSourceContext.pinTransactionDataSource(
                        R2dbcDataSourceContext.use("secondary", Mono.just("value"))));

        StepVerifier.create(nested).expectNext("value").verifyComplete();
    }
    @Test
    void routingContextIsVisibleWhenTransactionConnectionIsAcquired() {
        Connection primaryConnection = mock(Connection.class);
        Connection secondaryConnection = mock(Connection.class);
        ConnectionFactory primary = mock(ConnectionFactory.class);
        ConnectionFactory secondary = mock(ConnectionFactory.class);
        doReturn(Mono.just(primaryConnection)).when(primary).create();
        doReturn(Mono.just(secondaryConnection)).when(secondary).create();
        DynamicRoutingConnectionFactory routing = new DynamicRoutingConnectionFactory(
                "primary", Map.of("primary", primary, "secondary", secondary));

        StepVerifier.create(R2dbcDataSourceContext.use("secondary", Mono.from(routing.create())))
                .assertNext(connection -> assertThat(connection).isSameAs(secondaryConnection))
                .verifyComplete();

        verify(secondary).create();
        verify(primary, never()).create();
    }

    @Test
    void springTransactionalAdvisorRejectsDataSourceSwitch() {
        RoutingFixture fixture = routingFixture();
        ReactiveTransactionManager manager = new R2dbcTransactionManager(fixture.routing);
        ProxyFactory proxyFactory = new ProxyFactory(new TransactionalService(fixture.routing));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                manager, new AnnotationTransactionAttributeSource()));
        TransactionalService service = (TransactionalService) proxyFactory.getProxy();

        StepVerifier.create(R2dbcDataSourceContext.use("primary", service.switchToSecondary()))
                .expectErrorSatisfies(DataSourceTransactionOrderingTests::assertSwitchRejected)
                .verify();

        verify(fixture.secondaryFactory, never()).create();
    }

    @Test
    void wcdkTransactionPathRejectsDataSourceSwitch() {
        RoutingFixture fixture = routingFixture();
        ReactiveTransactionManager manager = new R2dbcTransactionManager(fixture.routing);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new TransactionalService(fixture.routing));
        proxyFactory.addAspect(new TransactionalAspect(manager));
        TransactionalService service = proxyFactory.getProxy();

        StepVerifier.create(R2dbcDataSourceContext.use("primary", service.switchToSecondary()))
                .expectErrorSatisfies(DataSourceTransactionOrderingTests::assertSwitchRejected)
                .verify();

        verify(fixture.secondaryFactory, never()).create();
    }

    @Test
    void transactionTemplateRejectsDataSourceSwitch() {
        RoutingFixture fixture = routingFixture();
        TransactionTemplate template = new TransactionTemplate(new TransactionManager(fixture.routing));

        StepVerifier.create(template.execute(connection -> R2dbcDataSourceContext.use("secondary",
                        Mono.from(fixture.routing.create()))))
                .expectErrorSatisfies(DataSourceTransactionOrderingTests::assertSwitchRejected)
                .verify();

        verify(fixture.secondaryFactory, never()).create();
    }

    @Test
    void customReactiveTransactionManagerStillUsesConnectionFactoryGuard() {
        RoutingFixture fixture = routingFixture();
        ReactiveTransactionManager custom = new DelegatingTransactionManager(
                new R2dbcTransactionManager(fixture.routing));
        TransactionalOperator operator = TransactionalOperator.create(custom);
        Mono<Void> work = R2dbcDataSourceContext.use("secondary",
                Mono.from(fixture.routing.create()).then());

        StepVerifier.create(R2dbcDataSourceContext.use("primary", operator.transactional(work)))
                .expectErrorSatisfies(DataSourceTransactionOrderingTests::assertSwitchRejected)
                .verify();

        verify(fixture.secondaryFactory, never()).create();
    }

    @Test
    void noTransactionAllowsIndependentDataSourceSelections() {
        RoutingFixture fixture = routingFixture();

        StepVerifier.create(Mono.from(fixture.routing.create())
                        .then(R2dbcDataSourceContext.use("secondary", Mono.from(fixture.routing.create()))))
                .assertNext(connection -> assertThat(connection).isSameAs(fixture.secondaryConnection))
                .verifyComplete();

        verify(fixture.primaryFactory).create();
        verify(fixture.secondaryFactory).create();
    }

    @Test
    void businessNoTransactionExceptionIsNeverReplayedForMonoOrFlux() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var error = new org.springframework.transaction.NoTransactionException("business");
        Mono<String> work = Mono.defer(() -> { count.incrementAndGet(); return Mono.error(error); });
        StepVerifier.create(R2dbcDataSourceContext.use("primary", work))
                .expectErrorSatisfies(actual -> assertThat(actual).isSameAs(error)).verify();
        assertThat(count).hasValue(1);
        StepVerifier.create(R2dbcDataSourceContext.use("primary", work.flux()))
                .expectErrorSatisfies(actual -> assertThat(actual).isSameAs(error)).verify();
        assertThat(count).hasValue(2);
    }

    @Test
    void realPrimaryAndSingleFactoryAliasesAreAccepted() {
        RoutingFixture fixture = routingFixture();
        TransactionTemplate template = new TransactionTemplate(new TransactionManager(fixture.routing));
        StepVerifier.create(template.execute(connection -> R2dbcDataSourceContext.use("primary", Mono.just("ok"))))
                .expectNext("ok").verifyComplete();
        TransactionTemplate single = new TransactionTemplate(new TransactionManager(fixture.primaryFactory));
        StepVerifier.create(single.execute(connection -> R2dbcDataSourceContext.use("any-alias", Mono.just("ok"))))
                .expectNext("ok").verifyComplete();
        StepVerifier.create(TransactionalOperator.create(new WcdkReactiveTransactionManager(fixture.primaryFactory))
                .transactional(R2dbcDataSourceContext.use("any-alias", Mono.just("ok"))))
                .expectNext("ok").verifyComplete();
    }

    @Test
    void scopedManualTransactionRejectsSwitchAndAcceptsExplicitPrimary() {
        RoutingFixture fixture = routingFixture();
        TransactionTemplate template = new TransactionTemplate(new TransactionManager(fixture.routing));
        StepVerifier.create(template.executeManual(transaction -> R2dbcDataSourceContext.use("secondary",
                        Mono.just("bad"))))
                .expectErrorSatisfies(DataSourceTransactionOrderingTests::assertSwitchRejected).verify();
        StepVerifier.create(template.executeManual(transaction -> R2dbcDataSourceContext.use("primary",
                        transaction.commit().thenReturn("ok"))))
                .expectNext("ok").verifyComplete();
    }

    @Test
    void requiresNewPinsSecondaryAndRestoresOuterPrimary() {
        for (boolean wrapped : new boolean[]{false, true}) {
            RoutingFixture fixture = routingFixture();
            ReactiveTransactionManager manager = wrapped ? new WcdkReactiveTransactionManager(fixture.routing)
                    : new R2dbcTransactionManager(fixture.routing);
            var outer = TransactionalOperator.create(manager);
            var definition = new org.springframework.transaction.support.DefaultTransactionDefinition(
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            var inner = TransactionalOperator.create(manager, definition);
            Mono<String> work = Mono.defer(() -> R2dbcDataSourceContext.use("secondary", inner,
                    Mono.defer(() -> R2dbcDataSourceContext.use("secondary", Mono.just("inner")))))
                    .doOnNext(value -> assertThat(value).isEqualTo("inner"))
                    .then(R2dbcDataSourceContext.use("primary", Mono.just("outer")))
                    .flatMap(value -> R2dbcDataSourceContext.use("secondary", Mono.just(value)));
            StepVerifier.create(outer.transactional(work))
                    .expectErrorSatisfies(DataSourceTransactionOrderingTests::assertSwitchRejected).verify();
            verify(fixture.secondaryConnection).commitTransaction();
            verify(fixture.primaryConnection).rollbackTransaction();
        }
    }

    @Test
    void notSupportedAllowsSelectionAndRestoresOuterGuard() {
        RoutingFixture fixture = routingFixture();
        var manager = new WcdkReactiveTransactionManager(fixture.routing);
        var outer = TransactionalOperator.create(manager);
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var inner = TransactionalOperator.create(manager, definition);
        Mono<Void> work = inner.transactional(R2dbcDataSourceContext.use("secondary",
                Mono.from(fixture.routing.create()).then()))
                .then(R2dbcDataSourceContext.use("secondary", Mono.just("rejected"))).then();
        StepVerifier.create(outer.transactional(work))
                .expectErrorSatisfies(DataSourceTransactionOrderingTests::assertSwitchRejected).verify();
        verify(fixture.secondaryFactory).create();
        verify(fixture.secondaryConnection, never()).beginTransaction(any(io.r2dbc.spi.TransactionDefinition.class));
    }

    @Test
    void supportsWithoutActualTransactionDoesNotBindOrValidate() {
        RoutingFixture fixture = routingFixture();
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition(
                TransactionDefinition.PROPAGATION_SUPPORTS);
        var operator = TransactionalOperator.create(new WcdkReactiveTransactionManager(fixture.routing), definition);
        Mono<String> work = Mono.from(fixture.routing.create()).then(R2dbcDataSourceContext.use("secondary",
                Mono.from(fixture.routing.create()))).then(R2dbcDataSourceContext.currentManager()
                .map(manager -> {
                    assertThat(manager.isActualTransactionActive()).isFalse();
                    assertThat(manager.hasResource(R2dbcDataSourceContext.transactionResourceKey())).isFalse();
                    return "ok";
                }));
        StepVerifier.create(operator.transactional(work)).expectNext("ok").verifyComplete();
    }

    @Test
    void requiredScopeCannotUseSecondaryConnectionFromPrimaryTransaction() {
        for (boolean wrapped : new boolean[]{false, true}) {
            RoutingFixture fixture = routingFixture();
            ReactiveTransactionManager manager = wrapped ? new WcdkReactiveTransactionManager(fixture.routing)
                    : new R2dbcTransactionManager(fixture.routing);
            var required = TransactionalOperator.create(manager);
            StepVerifier.create(required.transactional(R2dbcDataSourceContext.use("secondary", required,
                            Mono.from(fixture.routing.create()).thenReturn("bad"))))
                    .expectErrorSatisfies(DataSourceTransactionOrderingTests::assertSwitchRejected).verify();
            verify(fixture.secondaryFactory, never()).create();
        }
    }

    @Test
    void requiresNewRejectsChangingItsOwnPinnedDataSource() {
        RoutingFixture fixture = routingFixture();
        var manager = new WcdkReactiveTransactionManager(fixture.routing);
        var inner = TransactionalOperator.create(manager,
                new org.springframework.transaction.support.DefaultTransactionDefinition(
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        StepVerifier.create(TransactionalOperator.create(manager).transactional(
                        R2dbcDataSourceContext.use("secondary", inner,
                                R2dbcDataSourceContext.use("primary", Mono.just("bad")))))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("从 secondary 切换到 primary")).verify();
        verify(fixture.secondaryConnection).rollbackTransaction();
        verify(fixture.primaryConnection).rollbackTransaction();
    }

    @Test
    void requiresNewFailureRestoresOuterGuardAfterInnerRollback() {
        RoutingFixture fixture = routingFixture();
        var manager = new WcdkReactiveTransactionManager(fixture.routing);
        var inner = TransactionalOperator.create(manager,
                new org.springframework.transaction.support.DefaultTransactionDefinition(
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        var marker = new IllegalArgumentException("inner failure");
        Mono<String> work = R2dbcDataSourceContext.use("secondary", inner, Mono.<String>error(marker))
                .onErrorResume(IllegalArgumentException.class, error -> {
                    assertThat(error).isSameAs(marker);
                    return R2dbcDataSourceContext.use("primary", Mono.just("restored"));
                });
        StepVerifier.create(TransactionalOperator.create(manager).transactional(work))
                .expectNext("restored").verifyComplete();
        verify(fixture.secondaryConnection).rollbackTransaction();
        verify(fixture.primaryConnection).commitTransaction();
    }

    @Test
    void fluxScopeSupportsRequiresNewAndNotSupportedWithoutGuardBypass() {
        for (int propagation : new int[]{TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED}) {
            RoutingFixture fixture = routingFixture();
            var manager = new WcdkReactiveTransactionManager(fixture.routing);
            var inner = TransactionalOperator.create(manager,
                    new org.springframework.transaction.support.DefaultTransactionDefinition(propagation));
            reactor.core.publisher.Flux<String> work = R2dbcDataSourceContext.use("secondary", inner,
                    R2dbcDataSourceContext.use("secondary", Mono.from(fixture.routing.create())
                            .thenMany(reactor.core.publisher.Flux.just("one", "two"))))
                    .concatWith(R2dbcDataSourceContext.use("primary", Mono.just("restored")));
            StepVerifier.create(TransactionalOperator.create(manager).transactional(work))
                    .expectNext("one", "two", "restored").verifyComplete();
            verify(fixture.primaryConnection).commitTransaction();
        }
    }

    @Test
    void missingDelegateContextExplainsCauseAndRepair() {
        var delegate = mock(ReactiveTransactionManager.class);
        var transaction = mock(ReactiveTransaction.class);
        when(delegate.getReactiveTransaction(any())).thenReturn(Mono.just(transaction));
        StepVerifier.create(new WcdkReactiveTransactionManager(delegate, "primary")
                        .getReactiveTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition()))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("未建立响应式事务上下文").hasMessageContaining("无法锁定数据源")
                        .hasMessageContaining("R2dbcTransactionManager").hasMessageContaining("TransactionalOperator")
                        .hasCauseInstanceOf(org.springframework.transaction.NoTransactionException.class)).verify();
    }

    private static void assertSwitchRejected(Throwable error) {
        assertThat(error)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事务开始后无法将 R2DBC 数据源")
                .hasMessageContaining("primary")
                .hasMessageContaining("secondary");
    }

    private RoutingFixture routingFixture() {
        Connection primaryConnection = transactionConnection();
        Connection secondaryConnection = transactionConnection();
        ConnectionFactory primaryFactory = mock(ConnectionFactory.class);
        ConnectionFactory secondaryFactory = mock(ConnectionFactory.class);
        doReturn(Mono.just(primaryConnection)).when(primaryFactory).create();
        doReturn(Mono.just(secondaryConnection)).when(secondaryFactory).create();
        DynamicRoutingConnectionFactory routing = new DynamicRoutingConnectionFactory(
                "primary", Map.of("primary", primaryFactory, "secondary", secondaryFactory));
        return new RoutingFixture(routing, primaryFactory, secondaryFactory,
                primaryConnection, secondaryConnection);
    }

    private Connection transactionConnection() {
        Connection connection = mock(Connection.class);
        when(connection.isAutoCommit()).thenReturn(true);
        doReturn(Mono.empty()).when(connection).beginTransaction(any(io.r2dbc.spi.TransactionDefinition.class));
        doReturn(Mono.empty()).when(connection).commitTransaction();
        doReturn(Mono.empty()).when(connection).rollbackTransaction();
        doReturn(Mono.empty()).when(connection).close();
        return connection;
    }

    private record RoutingFixture(DynamicRoutingConnectionFactory routing,
                                  ConnectionFactory primaryFactory,
                                  ConnectionFactory secondaryFactory,
                                  Connection primaryConnection,
                                  Connection secondaryConnection) {
    }

    private record DelegatingTransactionManager(ReactiveTransactionManager delegate)
            implements ReactiveTransactionManager {

        @Override
        public Mono<ReactiveTransaction> getReactiveTransaction(TransactionDefinition definition) {
            return delegate.getReactiveTransaction(definition);
        }

        @Override
        public Mono<Void> commit(ReactiveTransaction transaction) {
            return delegate.commit(transaction);
        }

        @Override
        public Mono<Void> rollback(ReactiveTransaction transaction) {
            return delegate.rollback(transaction);
        }
    }

    public static class TransactionalService {

        private final DynamicRoutingConnectionFactory routing;

        public TransactionalService(DynamicRoutingConnectionFactory routing) {
            this.routing = routing;
        }

        @Transactional
        public Mono<Void> switchToSecondary() {
            return R2dbcDataSourceContext.use("secondary", Mono.from(routing.create()).then());
        }
    }
}
