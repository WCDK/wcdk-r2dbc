package com.wcdk.r2dbc;

import com.wcdk.r2dbc.core.RepositoryOperations;
import com.wcdk.r2dbc.core.executor.SqlLifecycleExecutor;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.BiFunction;

/***
 * R2DBC Repository 内部操作适配器。
 * @author wcdk
 */
public final class R2dbcRepositoryOperations implements RepositoryOperations {
    private final R2dbcUtil delegate;

    public R2dbcRepositoryOperations(R2dbcUtil delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public DatabaseClient databaseClient() {
        return delegate.databaseClient();
    }

    @Override
    public SqlLifecycleExecutor lifecycleExecutor() {
        return delegate.getLifecycleExecutorInternal();
    }

    @Override
    public <T> Flux<T> queryWithoutLifecycle(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return delegate.queryWithoutLifecycle(sql, parameters, mapper);
    }

    @Override
    public <T> Mono<T> queryOneWithoutLifecycle(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return delegate.queryOneWithoutLifecycle(sql, parameters, mapper);
    }

    @Override
    public <T> Flux<T> query(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return delegate.query(sql, parameters, mapper);
    }

    @Override
    public <T> Mono<T> queryOne(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return delegate.queryOne(sql, parameters, mapper);
    }

    @Override
    public Mono<Long> updateWithoutLifecycle(String sql, Map<?, ?> parameters) {
        return delegate.updateWithoutLifecycle(sql, parameters);
    }

    @Override
    public <T> T map(Row row, Class<T> entityClass) {
        return delegate.map(row, entityClass);
    }

    @Override
    public Object convertValue(Object value, Class<?> targetType) {
        return delegate.convertValue(value, targetType);
    }
}