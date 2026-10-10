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
