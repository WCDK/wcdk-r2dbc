package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.execution.ExecutionOptions;
import com.wcdk.r2dbc.execution.SqlExecutionRequest;
import com.wcdk.r2dbc.execution.SqlLifecycleExecutor;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/***
 * Repository 唯一 SQL 执行入口。
 * @author wcdk
 **/
final class SqlExecutionEngine {
    private final RepositoryOperations operations;

    SqlExecutionEngine(RepositoryOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    SqlLifecycleExecutor lifecycleExecutor() {
        return operations.lifecycleExecutor();
    }

    /***
     * 执行统一 SQL 查询请求。
     *
     * @param request SQL 执行请求
     * @param <T> 结果类型
     * @return 查询结果流
     * @author wcdk
     **/
    <T> Flux<T> query(SqlExecutionRequest<T> request) {
        return operations.queryWithoutLifecycle(request.sql(), request.namedParameters(), request.mapper());
    }

    /***
     * 执行统一 SQL 更新请求。
     *
     * @param request SQL 执行请求
     * @return 更新行数
     * @author wcdk
     **/
    Mono<Long> update(SqlExecutionRequest<?> request) {
        return operations.updateWithoutLifecycle(request.sql(), request.namedParameters());
    }

    <T> Flux<T> query(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return query(SqlExecutionRequest.query(sql, parameters, null, mapper));
    }

    <T> Flux<T> queryWithoutLifecycle(String sql, Map<?, ?> parameters,
                                      BiFunction<Row, RowMetadata, T> mapper) {
        return query(SqlExecutionRequest.query(sql, parameters, null, mapper));
    }

    <T> Mono<T> queryOneWithoutLifecycle(String sql, Map<?, ?> parameters,
                                         BiFunction<Row, RowMetadata, T> mapper) {
        return query(SqlExecutionRequest.query(sql, parameters, null, mapper)).next();
    }

    Mono<Long> updateWithoutLifecycle(String sql, Map<?, ?> parameters) {
        return update(SqlExecutionRequest.update(sql, parameters));
    }

    <T> T map(Row row, Class<T> type) {
        return operations.map(row, type);
    }

    Object convertValue(Object value, Class<?> type) {
        return operations.convertValue(value, type);
    }

    io.r2dbc.spi.ConnectionFactory connectionFactory() {
        return operations.databaseClient().getConnectionFactory();
    }
}