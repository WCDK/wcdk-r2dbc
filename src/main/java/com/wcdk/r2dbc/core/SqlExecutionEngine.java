package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.core.executor.SqlLifecycleExecutor;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.BiFunction;

/***
 * Repository SQL 执行引擎。
 * @author wcdk
 */
final class SqlExecutionEngine {
    private final RepositoryOperations operations;

    SqlExecutionEngine(RepositoryOperations operations) {
        this.operations = java.util.Objects.requireNonNull(operations, "operations");
    }

    SqlLifecycleExecutor lifecycleExecutor() { return operations.lifecycleExecutor(); }

    <T> Flux<T> query(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return operations.query(sql, parameters, mapper);
    }

    <T> Flux<T> queryWithoutLifecycle(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return operations.queryWithoutLifecycle(sql, parameters, mapper);
    }

    <T> Mono<T> queryOneWithoutLifecycle(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return operations.queryOneWithoutLifecycle(sql, parameters, mapper);
    }

    Mono<Long> updateWithoutLifecycle(String sql, Map<?, ?> parameters) {
        return operations.updateWithoutLifecycle(sql, parameters);
    }

    <T> T map(Row row, Class<T> type) { return operations.map(row, type); }

    Object convertValue(Object value, Class<?> type) { return operations.convertValue(value, type); }

    io.r2dbc.spi.ConnectionFactory connectionFactory() {
        return operations.databaseClient().getConnectionFactory();
    }
}