package com.wcdk.r2dbc.core;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.core.DatabaseClient;
import com.wcdk.r2dbc.core.executor.SqlLifecycleExecutor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.BiFunction;

/***
 * Repository 内部执行访问接口。
 * @author wcdk
 */
public interface RepositoryOperations {
    DatabaseClient databaseClient();

    SqlLifecycleExecutor lifecycleExecutor();

    <T> Flux<T> queryWithoutLifecycle(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper);

    <T> Mono<T> queryOneWithoutLifecycle(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper);

    <T> Flux<T> query(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper);

    <T> Mono<T> queryOne(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper);

    Mono<Long> updateWithoutLifecycle(String sql, Map<?, ?> parameters);

    <T> T map(Row row, Class<T> entityClass);

    Object convertValue(Object value, Class<?> targetType);
}