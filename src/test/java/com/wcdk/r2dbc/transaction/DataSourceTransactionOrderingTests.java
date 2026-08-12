package com.wcdk.r2dbc.transaction;

import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceAspect;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
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
                        && error.getMessage().contains("事务开始后无法将R2DBC数据源"))
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
}