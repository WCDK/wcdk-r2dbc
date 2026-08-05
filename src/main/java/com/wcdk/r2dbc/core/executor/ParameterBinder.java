package com.wcdk.r2dbc.core.executor;

import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Map;

/**
 * R2DBC参数绑定器，负责将参数绑定到SQL执行规范。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class ParameterBinder {

    /**
     * 创建绑定后的执行规范。
     *
     * @param databaseClient 数据库客户端
     * @param sql            SQL语句
     * @param parameters     参数
     * @return 绑定后的执行规范
     */
    public DatabaseClient.GenericExecuteSpec bind(DatabaseClient databaseClient, String sql, Map<?, ?> parameters) {
        String requiredSql = requireSql(sql);
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(requiredSql);
        if (parameters == null || parameters.isEmpty()) {
            return spec;
        }
        for (Map.Entry<?, ?> entry : parameters.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key instanceof Number numberKey) {
                spec = bind(spec, numberKey.intValue(), value);
                continue;
            }
            spec = bind(spec, String.valueOf(key), value);
        }
        return spec;
    }

    /**
     * 按索引绑定参数。
     *
     * @param spec  执行规范
     * @param index 索引
     * @param value 值
     * @return 绑定后的执行规范
     */
    public DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, int index, Object value) {
        if (value == null) {
            return spec.bindNull(index, Object.class);
        }
        return spec.bind(index, value);
    }

    /**
     * 按名称绑定参数。
     *
     * @param spec       执行规范
     * @param identifier 参数名
     * @param value      值
     * @return 绑定后的执行规范
     */
    public DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, String identifier, Object value) {
        if (value == null) {
            return spec.bindNull(identifier, Object.class);
        }
        return spec.bind(identifier, value);
    }

    private String requireSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("R2DBC SQL is blank");
        }
        return sql;
    }
}
