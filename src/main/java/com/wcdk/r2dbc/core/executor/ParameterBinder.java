package com.wcdk.r2dbc.core.executor;

import org.springframework.r2dbc.core.DatabaseClient;
import io.r2dbc.spi.Parameters;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
            Set<String> required = namedParameters(requiredSql);
            if (!required.isEmpty()) {
                throw new IllegalArgumentException("Missing SQL parameters " + required + " for SQL: " + requiredSql);
            }
            return spec;
        }
        Set<String> required = namedParameters(requiredSql);
        Set<String> supplied = new LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : parameters.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key instanceof Number numberKey) {
                int index = numberKey.intValue();
                if (index < 0) {
                    throw new IllegalArgumentException("SQL parameter index must not be negative: " + index
                            + ", SQL: " + requiredSql);
                }
                spec = bind(spec, index, value);
                continue;
            }
            String identifier = String.valueOf(key);
            requireIdentifier(identifier, requiredSql);
            supplied.add(identifier);
            spec = bind(spec, identifier, value);
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(supplied);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing SQL parameters " + missing + " for SQL: " + requiredSql);
        }
        Set<String> unused = new LinkedHashSet<>(supplied);
        unused.removeAll(required);
        if (!unused.isEmpty() && !required.isEmpty()) {
            throw new IllegalArgumentException("Unused SQL parameters " + unused + " for SQL: " + requiredSql);
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
        if (index < 0) {
            throw new IllegalArgumentException("SQL parameter index must not be negative: " + index);
        }
        if (value instanceof SqlParameter parameter) {
            if (parameter.databaseType() != null) {
                return spec.bind(index, parameter.value() == null
                        ? Parameters.in(parameter.databaseType())
                        : Parameters.in(parameter.databaseType(), parameter.value()));
            }
            return parameter.value() == null
                    ? spec.bindNull(index, parameter.javaType())
                    : spec.bind(index, parameter.value());
        }
        if (value == null) {
            throw new IllegalArgumentException("Null SQL parameter at index " + index
                    + " requires SqlParameter.nullOf(type)");
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
        requireIdentifier(identifier, null);
        if (value instanceof SqlParameter parameter) {
            if (parameter.databaseType() != null) {
                return spec.bind(identifier, parameter.value() == null
                        ? Parameters.in(parameter.databaseType())
                        : Parameters.in(parameter.databaseType(), parameter.value()));
            }
            return parameter.value() == null
                    ? spec.bindNull(identifier, parameter.javaType())
                    : spec.bind(identifier, parameter.value());
        }
        if (value == null) {
            throw new IllegalArgumentException("Null SQL parameter '" + identifier
                    + "' requires SqlParameter.nullOf(type)");
        }
        return spec.bind(identifier, value);
    }

    private String requireSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("R2DBC SQL is blank");
        }
        return sql;
    }

    private void requireIdentifier(String identifier, String sql) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("SQL parameter name must not be blank"
                    + (sql == null ? "" : ", SQL: " + sql));
        }
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL parameter name '" + identifier + "'"
                    + (sql == null ? "" : ", SQL: " + sql));
        }
    }

    static Set<String> namedParameters(String sql) {
        Set<String> names = new LinkedHashSet<>();
        boolean single = false;
        boolean quoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!single && !quoted && ch == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!single && !quoted && ch == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (ch == '\'' && !quoted) {
                if (single && next == '\'') {
                    i++;
                    continue;
                }
                single = !single;
                continue;
            }
            if (ch == '"' && !single) {
                quoted = !quoted;
                continue;
            }
            if (!single && !quoted && ch == ':' && next != ':' && i > 0 && sql.charAt(i - 1) == ':') {
                continue;
            }
            if (!single && !quoted && ch == ':' && (Character.isLetter(next) || next == '_')) {
                int end = i + 2;
                while (end < sql.length()) {
                    char candidate = sql.charAt(end);
                    if (!Character.isLetterOrDigit(candidate) && candidate != '_') break;
                    end++;
                }
                names.add(sql.substring(i + 1, end));
                i = end - 1;
            }
        }
        return names;
    }
}
