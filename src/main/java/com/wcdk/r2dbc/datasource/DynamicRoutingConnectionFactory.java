package com.wcdk.r2dbc.datasource;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @auther WCDK
 * @date 2026/7/27
 * @version 1.0
 **/
public class DynamicRoutingConnectionFactory implements ConnectionFactory, Disposable {

    private final String primary;

    private final Map<String, ConnectionFactory> connectionFactories;

    private final AtomicBoolean disposed = new AtomicBoolean();

    public DynamicRoutingConnectionFactory(String primary, Map<String, ConnectionFactory> connectionFactories) {
        if (connectionFactories == null || connectionFactories.isEmpty()) {
            throw new IllegalArgumentException("R2DBC data sources must not be empty");
        }
        if (primary == null || primary.isBlank()) {
            throw new IllegalArgumentException("R2DBC primary data source key is blank");
        }
        if (!connectionFactories.containsKey(primary)) {
            throw new IllegalArgumentException("R2DBC primary data source does not exist: " + primary);
        }
        this.primary = primary;
        this.connectionFactories = Collections.unmodifiableMap(new LinkedHashMap<>(connectionFactories));
    }

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.deferContextual(contextView -> Mono.from(determineConnectionFactory(contextView).create()));
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return connectionFactories.get(primary).getMetadata();
    }

    public Map<String, ConnectionFactory> getConnectionFactories() {
        return connectionFactories;
    }

    public String getPrimary() {
        return primary;
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        Set<ConnectionFactory> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ConnectionFactory connectionFactory : connectionFactories.values()) {
            if (!unique.add(connectionFactory)) {
                continue;
            }
            if (connectionFactory instanceof Disposable disposable) {
                try {
                    disposable.dispose();
                } catch (RuntimeException error) {
                    if (failure == null) {
                        failure = new IllegalStateException("Failed to dispose one or more R2DBC data sources");
                    }
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean isDisposed() {
        return disposed.get();
    }

    private ConnectionFactory determineConnectionFactory(ContextView contextView) {
        String dataSource = R2dbcDataSourceContext.get(contextView);
        String key = dataSource == null || dataSource.isBlank() ? primary : dataSource;
        ConnectionFactory connectionFactory = connectionFactories.get(key);
        if (connectionFactory == null) {
            throw new IllegalArgumentException("R2DBC data source does not exist: " + key
                    + "; available keys: " + connectionFactories.keySet());
        }
        return connectionFactory;
    }
}
