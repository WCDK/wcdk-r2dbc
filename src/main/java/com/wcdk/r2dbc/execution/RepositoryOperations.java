package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.core.executor.SqlLifecycleExecutor;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.BiFunction;

/***
 * 仓储执行阶段访问接口。
 * @author wcdk
 **/
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
