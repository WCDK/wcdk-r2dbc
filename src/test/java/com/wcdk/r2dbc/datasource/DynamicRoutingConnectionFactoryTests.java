package com.wcdk.r2dbc.datasource;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicRoutingConnectionFactoryTests {

    @Test
    void disposesEveryManagedConnectionFactory() {
        DisposableConnectionFactory master = new DisposableConnectionFactory();
        DisposableConnectionFactory reporting = new DisposableConnectionFactory();
        DynamicRoutingConnectionFactory routing = new DynamicRoutingConnectionFactory(
                "master", Map.of("master", master, "reporting", reporting));

        routing.dispose();
        routing.dispose();

        assertThat(master.isDisposed()).isTrue();
        assertThat(reporting.isDisposed()).isTrue();
        assertThat(master.disposeCount).isEqualTo(1);
        assertThat(reporting.disposeCount).isEqualTo(1);
        assertThat(routing.isDisposed()).isTrue();
    }

    @Test
    void continuesDisposingAfterFailureAndAggregatesTheError() {
        DisposableConnectionFactory failing = new DisposableConnectionFactory(true);
        DisposableConnectionFactory healthy = new DisposableConnectionFactory();
        DynamicRoutingConnectionFactory routing = new DynamicRoutingConnectionFactory(
                "master", Map.of("master", failing, "reporting", healthy));

        assertThatThrownBy(routing::dispose)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispose")
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
        assertThat(failing.disposeCount).isEqualTo(1);
        assertThat(healthy.disposeCount).isEqualTo(1);

        routing.dispose();
        assertThat(failing.disposeCount).isEqualTo(1);
        assertThat(healthy.disposeCount).isEqualTo(1);
    }

    @Test
    void unknownKeyReportsOnlyRequestedAndAvailableKeys() {
        DynamicRoutingConnectionFactory routing = new DynamicRoutingConnectionFactory(
                "master", Map.of("master", new DisposableConnectionFactory(),
                        "reporting", new DisposableConnectionFactory()));

        StepVerifier.create(R2dbcDataSourceContext.use("missing", Mono.from(routing.create())))
                .expectErrorMatches(error -> error instanceof IllegalArgumentException
                        && error.getMessage().contains("missing")
                        && error.getMessage().contains("master")
                        && error.getMessage().contains("reporting"))
                .verify();
    }

    private static final class DisposableConnectionFactory implements ConnectionFactory, Disposable {
        private boolean disposed;
        private int disposeCount;
        private final boolean failOnDispose;

        private DisposableConnectionFactory() {
            this(false);
        }

        private DisposableConnectionFactory(boolean failOnDispose) {
            this.failOnDispose = failOnDispose;
        }

        @Override
        public Publisher<? extends Connection> create() {
            return Mono.empty();
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return () -> "test";
        }

        @Override
        public void dispose() {
            disposeCount++;
            disposed = true;
            if (failOnDispose) {
                throw new IllegalStateException("close failed");
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
