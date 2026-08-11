package com.wcdk.r2dbc.datasource;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicRoutingConnectionFactoryTests {

    @Test
    void disposesEveryManagedConnectionFactory() {
        DisposableConnectionFactory master = new DisposableConnectionFactory();
        DisposableConnectionFactory reporting = new DisposableConnectionFactory();
        DynamicRoutingConnectionFactory routing = new DynamicRoutingConnectionFactory(
                "master", Map.of("master", master, "reporting", reporting));

        routing.dispose();

        assertThat(master.isDisposed()).isTrue();
        assertThat(reporting.isDisposed()).isTrue();
        assertThat(routing.isDisposed()).isTrue();
    }

    private static final class DisposableConnectionFactory implements ConnectionFactory, Disposable {
        private boolean disposed;

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
            disposed = true;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
