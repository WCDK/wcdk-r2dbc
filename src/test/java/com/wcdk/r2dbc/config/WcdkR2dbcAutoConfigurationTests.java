package com.wcdk.r2dbc.config;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import com.wcdk.r2dbc.execution.ParameterBinder;
import com.wcdk.r2dbc.transaction.TransactionalAspect;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WcdkR2dbcAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WcdkR2dbcAutoConfiguration.class))
            .withPropertyValues("wcdk.r2dbc.enabled=true");

    @Test
    void backsOffWhenApplicationProvidesConnectionFactory() {
        ConnectionFactory supplied = mock(ConnectionFactory.class);
        when(supplied.getMetadata()).thenReturn(() -> "PostgreSQL");

        runner.withBean(ConnectionFactory.class, () -> supplied)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConnectionFactory.class);
                    assertThat(context.getBean(ConnectionFactory.class)).isSameAs(supplied);
                });
    }

    @Test
    void disablesWcdkTransactionalAspectByDefault() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.getMetadata()).thenReturn(() -> "PostgreSQL");

        runner.withBean(ConnectionFactory.class, () -> connectionFactory)
                .run(context -> assertThat(context).doesNotHaveBean(TransactionalAspect.class));
    }

    @Test
    void enablesWcdkTransactionalAspectOnlyWhenExplicitlyConfigured() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.getMetadata()).thenReturn(() -> "PostgreSQL");

        runner.withPropertyValues("wcdk.r2dbc.transaction.aspect-enabled=true")
                .withBean(ConnectionFactory.class, () -> connectionFactory)
                .run(context -> assertThat(context).hasSingleBean(TransactionalAspect.class));
    }

    @Test
    void backsOffWcdkTransactionalAspectWhenSpringAdvisorExists() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.getMetadata()).thenReturn(() -> "PostgreSQL");

        runner.withPropertyValues("wcdk.r2dbc.transaction.aspect-enabled=true")
                .withBean(ConnectionFactory.class, () -> connectionFactory)
                .withBean("org.springframework.transaction.config.internalTransactionAdvisor", Object.class, Object::new)
                .run(context -> assertThat(context).doesNotHaveBean(TransactionalAspect.class));
    }

    @Test
    void reportsDatabaseAndMavenCoordinatesWhenSpiDriverIsMissing() {
        runner.withPropertyValues(
                        "spring.r2dbc.url=r2dbc:postgresql://localhost/test",
                        "spring.r2dbc.pool.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("No R2DBC SPI provider found for database 'postgresql'")
                            .hasStackTraceContaining("org.postgresql:r2dbc-postgresql");
                });
    }

    @Test
    void disposesManagedConnectionFactoryWhenContextCloses() {
        DisposableConnectionFactory managed = new DisposableConnectionFactory();

        runner.withBean(ConnectionFactory.class, () -> managed)
                .run(context -> assertThat(managed.isDisposed()).isFalse());

        assertThat(managed.isDisposed()).isTrue();
    }

    @Test
    void backsOffForUserProvidedInfrastructureComponents() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.getMetadata()).thenReturn(() -> "PostgreSQL");
        ParameterBinder supplied = new ParameterBinder();

        runner.withBean(ConnectionFactory.class, () -> connectionFactory)
                .withBean(ParameterBinder.class, () -> supplied)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ParameterBinder.class);
                    assertThat(context.getBean(ParameterBinder.class)).isSameAs(supplied);
                });
    }

    @Test
    void reportsInvalidPrimaryBeforeCreatingDataSources() {
        runner.withPropertyValues(
                        "spring.r2dbc.data-sources.master.url=r2dbc:postgresql://localhost/test",
                        "spring.r2dbc.primary=missing")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("spring.r2dbc.primary='missing'")
                            .hasStackTraceContaining("available: [master]");
                });
    }

    private static final class DisposableConnectionFactory implements ConnectionFactory, Disposable {
        private boolean disposed;

        @Override
        public Publisher<? extends Connection> create() {
            return Mono.empty();
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return () -> "PostgreSQL";
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
