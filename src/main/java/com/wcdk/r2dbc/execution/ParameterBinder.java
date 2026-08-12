package com.wcdk.r2dbc.execution;

import org.springframework.r2dbc.core.DatabaseClient;
import io.r2dbc.spi.Parameters;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.sql.Time;
import java.sql.Timestamp;
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
                throw new IllegalArgumentException("缺少SQL参数 " + required + "，SQL: " + requiredSql);
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
                    throw new IllegalArgumentException("SQL参数索引不能为负数: " + index
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
            throw new IllegalArgumentException("缺少SQL参数 " + missing + "，SQL: " + requiredSql);
        }
        Set<String> unused = new LinkedHashSet<>(supplied);
        unused.removeAll(required);
        if (!unused.isEmpty() && !required.isEmpty()) {
            throw new IllegalArgumentException("未使用的SQL参数 " + unused + "，SQL: " + requiredSql);
        }
        return spec;
    }

    /**
     * 按统一参数模型绑定参数。参数带名称时按名称绑定，否则按索引绑定。
     *
     * @param spec 执行规范
     * @param parameters 统一SQL参数
     * @return 绑定后的执行规范
     * @author wcdk
     */
    public DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, java.util.List<SqlParameter> parameters) {
        if (parameters == null) return spec;
        DatabaseClient.GenericExecuteSpec current = spec;
        for (int index = 0; index < parameters.size(); index++) {
            SqlParameter parameter = java.util.Objects.requireNonNull(parameters.get(index), "SQL参数不能为空");
            current = parameter.name() == null || parameter.name().isBlank()
                    ? bind(current, index, parameter)
                    : bind(current, parameter.name(), parameter);
        }
        return current;
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
            throw new IllegalArgumentException("SQL参数索引不能为负数: " + index);
        }
        if (value instanceof SqlParameter parameter) {
            if (parameter.databaseType() != null) {
                return spec.bind(index, parameter.value() == null
                        ? Parameters.in(parameter.databaseType())
                        : Parameters.in(parameter.databaseType(), normalizeParameterValue(parameter.value())));
            }
            return parameter.value() == null
                    ? spec.bindNull(index, parameter.javaType())
                    : spec.bind(index, normalizeParameterValue(parameter.value()));
        }
        if (value == null) {
            throw new IllegalArgumentException("索引 " + index + " 处的空SQL参数需要使用SqlParameter.nullOf(type)");
        }
        return spec.bind(index, normalizeParameterValue(value));
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
                        : Parameters.in(parameter.databaseType(), normalizeParameterValue(parameter.value())));
            }
            return parameter.value() == null
                    ? spec.bindNull(identifier, parameter.javaType())
                    : spec.bind(identifier, normalizeParameterValue(parameter.value()));
        }
        if (value == null) {
            throw new IllegalArgumentException("空SQL参数 '" + identifier
                    + "' 需要使用SqlParameter.nullOf(type)");
        }
        return spec.bind(identifier, normalizeParameterValue(value));
    }

    /**
     * Normalize Java time values before they reach JDBC-backed R2DBC drivers.
     * The DM driver 1.0.0 timestamp converter accepts java.util.Date and casts
     * arbitrary values to Date in its Object overload.
     */
    static Object normalizeParameterValue(Object value) {
        if (value instanceof Instant instant) {
            return Date.from(instant);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime);
        }
        if (value instanceof LocalDate localDate) {
            return java.sql.Date.valueOf(localDate);
        }
        if (value instanceof LocalTime localTime) {
            return Time.valueOf(localTime);
        }
        return value;
    }
    private String requireSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("R2DBC SQL为空");
        }
        return sql;
    }

    private void requireIdentifier(String identifier, String sql) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("SQL参数名不能为空"
                    + (sql == null ? "" : ", SQL: " + sql));
        }
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("无效的SQL参数名 '" + identifier + "'"
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
