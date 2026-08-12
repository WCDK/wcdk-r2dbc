package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.repository.plan.SqlCommandType;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/***
 * 统一 SQL 执行请求。
 * @author wcdk
 **/
public record SqlExecutionRequest<T>(
        String sql,
        SqlCommandType commandType,
        List<SqlParameter> parameters,
        Class<T> resultType,
        ExecutionOptions options,
        Map<String, Object> namedParameters,
        BiFunction<Row, RowMetadata, T> mapper) {

    public SqlExecutionRequest {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(commandType, "commandType");
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        options = options == null ? ExecutionOptions.DEFAULT : options;
        namedParameters = Map.copyOf(namedParameters == null ? Map.of() : namedParameters);
    }

    /***
     * 创建带自定义行映射的查询请求。
     *
     * @param sql SQL
     * @param parameters 命名参数
     * @param resultType 结果类型
     * @param mapper 行映射器
     * @param <T> 结果类型
     * @return 查询请求
     * @author wcdk
     **/
    public static <T> SqlExecutionRequest<T> query(String sql, Map<?, ?> parameters,
                                                    Class<T> resultType,
                                                    BiFunction<Row, RowMetadata, T> mapper) {
        return new SqlExecutionRequest<>(sql, SqlCommandType.SELECT, List.of(), resultType,
                ExecutionOptions.DEFAULT, copy(parameters), mapper);
    }

    /***
     * 创建更新请求。
     *
     * @param sql SQL
     * @param parameters 命名参数
     * @return 更新请求
     * @author wcdk
     **/
    public static SqlExecutionRequest<Long> update(String sql, Map<?, ?> parameters) {
        return new SqlExecutionRequest<>(sql, SqlCommandType.UPDATE, List.of(), Long.class,
                ExecutionOptions.DEFAULT, copy(parameters), null);
    }

    private static Map<String, Object> copy(Map<?, ?> parameters) {
        if (parameters == null || parameters.isEmpty()) return Map.of();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        parameters.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}