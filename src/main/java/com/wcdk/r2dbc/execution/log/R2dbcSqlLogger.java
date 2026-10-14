package com.wcdk.r2dbc.execution.log;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import com.wcdk.r2dbc.execution.SqlParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.ContextView;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * R2DBC SQL日志记录器。
 *
 * @author WCDK
 * @version 1.0
 */
public class R2dbcSqlLogger {

    private static final Logger log = LoggerFactory.getLogger(R2dbcSqlLogger.class);
    private final WcdkR2dbcProperties properties;

    private final WcdkSpringR2dbcProperties springR2dbcProperties;

    public R2dbcSqlLogger(WcdkR2dbcProperties properties, WcdkSpringR2dbcProperties springR2dbcProperties) {
        this.properties = properties == null ? new WcdkR2dbcProperties() : properties;
        this.springR2dbcProperties = springR2dbcProperties == null ? new WcdkSpringR2dbcProperties() : springR2dbcProperties;
    }

    /**
     * 记录SQL日志。
     *
     * @param contextView 上下文视图
     * @param sql         SQL语句
     * @param parameters  参数
     */
    @Deprecated(since="3.6.0", forRemoval=true)
    public void logSql(ContextView contextView, String sql, Map<?, ?> parameters) {
        logExecution(contextView, sql, parameters, "START");
    }

    /**
     * 记录一次 SQL 执行实际返回或影响的行数。
     *
     * @param resultCount 返回或影响的行数
     */
    @Deprecated(since="3.6.0", forRemoval=true)
    public void logResultCount(long resultCount) {
        if (properties.isSqlLogEnabled()) {
            log.info("R2DBC执行结果，返回数量：{}", Math.max(0, resultCount));
        }
    }

    /**
     * 将一次 SQL 执行作为单个日志事件输出，避免并发执行时多行内容互相穿插。
     *
     * @param sql        SQL语句
     * @param parameters 参数
     * @param result     返回数量或错误状态
     */
    public void logExecution(String sql, Map<?, ?> parameters, Object result) {
        if (!properties.isSqlLogEnabled()) {
            return;
        }
        logExecution(UUID.randomUUID().toString(), sql, parameters, result);
    }

    public void logExecution(ContextView context, String sql, Map<?, ?> parameters, Object result) {
        if (!properties.isSqlLogEnabled()) return;
        if (context == null) { logExecution(sql, parameters, result); return; }
        var execution = context.<com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext>getOrDefault(
                com.wcdk.r2dbc.execution.SqlLifecycleExecutor.EXECUTION_CONTEXT_KEY, null);
        logExecution(execution == null ? UUID.randomUUID().toString() : execution.getExecutionId(), sql, parameters, result);
    }

    public void logExecution(String executionId, String sql, Map<?, ?> parameters, Object result) {
        if (!properties.isSqlLogEnabled()) return;
        try {
            String executableSql = completeSql(sql, parameters);
            log.info("R2DBC 执行日志 executionId={} SQL={} parameters={} result={}", executionId,
                    normalizeSql(sql), logParameters(parameters),
                    result instanceof Throwable error ? error.getClass().getSimpleName() + ": " + error.getMessage() : result);
            log.info("R2DBC 执行日志 executionId={} completeSql={}", executionId, executableSql);
        } catch (RuntimeException failure) {
            log.warn("SQL 日志格式化失败，仅记录标识：executionId={}, errorType={}", executionId, failure.getClass().getName());
        }
    }

    public boolean isEnabled() { return properties.isSqlLogEnabled(); }

    /** 兼容旧接口：文本原样返回，不再脱敏或截断。 */
    @Deprecated(since = "3.6.0")
    public String sanitizeText(String text, Map<?, ?> parameters) { return text; }

    /** 兼容旧接口：文本原样返回。 */
    @Deprecated(since = "3.6.0")
    public String sanitizeExecutionText(String text, String sql, Map<?, ?> parameters) { return text; }

    /** 兼容旧接口：SQL 原样返回。 */
    @Deprecated(since = "3.6.0")
    public String sanitizeExecutionSql(String sql, Map<?, ?> parameters) { return sql; }

    /** 兼容旧接口：仅展开带类型参数，保留所有参数值。 */
    @Deprecated(since = "3.6.0")
    public Map<String, Object> sanitizeParameters(String sql, Map<?, ?> parameters) { return logParameters(parameters); }

    /**
     * 将命名或位置参数替换为 SQL 字面量，保留原值与 SQL 词法结构。
     * @author wcdk
     */
    public String completeSql(String sql, Map<?, ?> parameters) {
        if (sql == null || parameters == null || parameters.isEmpty()) return sql;
        Map<Object, Object> bindings = new LinkedHashMap<>();
        parameters.forEach((key, value) -> bindings.put(key instanceof Number number ? number.intValue() : key, value));
        StringBuilder result = new StringBuilder(sql.length());
        int questionIndex = 0;
        int bracketDepth = 0;
        for (int i = 0; i < sql.length(); i++) {
            int end = com.wcdk.r2dbc.execution.SqlLexicalScanner.protectedEnd(sql, i);
            if (end > i) {
                result.append(sql, i, end);
                i = end - 1;
                continue;
            }
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (ch == '[') bracketDepth++;
            if (ch == ']') bracketDepth = Math.max(0, bracketDepth - 1);
            Object key = null;
            end = i + 1;
            if (ch == '?' && !com.wcdk.r2dbc.execution.NamedParameterParser.isJsonOperator(sql, i)) {
                key = questionIndex++;
            } else if ((ch == '$' || ch == ':' && bracketDepth == 0) && Character.isDigit(next)
                    && (i == 0 || sql.charAt(i - 1) != ':')) {
                end = i + 2;
                while (end < sql.length() && Character.isDigit(sql.charAt(end))) end++;
                key = Integer.parseInt(sql.substring(i + 1, end)) - 1;
            } else if (ch == ':' && bracketDepth == 0 && isParameterStart(next)
                    && (i == 0 || sql.charAt(i - 1) != ':')) {
                end = i + 2;
                while (end < sql.length() && isParameterPart(sql.charAt(end))) end++;
                key = sql.substring(i + 1, end);
            }
            if (key != null && bindings.containsKey(key)) {
                result.append(toSqlLiteral(bindings.get(key)));
                i = end - 1;
            } else result.append(ch);
        }
        return result.toString();
    }

    private String toSqlLiteral(Object value) {
        if (value instanceof SqlParameter parameter) value = parameter.value();
        if (value == null) return "NULL";
        if (value instanceof Enum<?> enumeration) return quoteSqlString(enumeration.name());
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean bool) return bool ? "TRUE" : "FALSE";
        if (value instanceof Iterable<?> iterable) {
            StringBuilder values = new StringBuilder();
            for (Object item : iterable) {
                if (!values.isEmpty()) values.append(", ");
                values.append(toSqlLiteral(item));
            }
            return values.isEmpty() ? "NULL" : values.toString();
        }
        if (value.getClass().isArray() && !(value instanceof byte[])) {
            StringBuilder values = new StringBuilder();
            for (int i = 0; i < Array.getLength(value); i++) {
                if (!values.isEmpty()) values.append(", ");
                values.append(toSqlLiteral(Array.get(value, i)));
            }
            return values.isEmpty() ? "NULL" : values.toString();
        }
        if (value instanceof byte[] bytes) return "X'" + java.util.HexFormat.of().formatHex(bytes) + "'";
        if (value instanceof TemporalAccessor || value instanceof Date || value instanceof UUID
                || value instanceof Character || value instanceof CharSequence || value instanceof Enum<?>) {
            return quoteSqlString(String.valueOf(value));
        }
        return quoteSqlString(String.valueOf(value));
    }

    private String quoteSqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private boolean isParameterStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private boolean isParameterPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    /** 兼容旧接口：参数值原样保留。 */
    @Deprecated(since = "3.6.0")
    public Map<String, Object> sanitizeParameters(Map<?, ?> parameters) { return logParameters(parameters); }

    /** 展开日志参数；不根据名称或内容脱敏，不截断字符串与集合。 */
    public Map<String, Object> logParameters(Map<?, ?> parameters) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (parameters != null) parameters.forEach((key, value) -> values.put(String.valueOf(key),
                value instanceof SqlParameter parameter ? parameter.value() : value));
        return java.util.Collections.unmodifiableMap(values);
    }

    /**
     * 获取当前数据源名称。
     *
     * @param contextView 上下文视图
     * @return 数据源名称
     */
    public String currentDataSource(ContextView contextView) {
        String dataSource = R2dbcDataSourceContext.get(contextView);
        if (dataSource != null && !dataSource.isBlank()) {
            return dataSource;
        }
        String primary = springR2dbcProperties.getPrimary();
        return primary == null || primary.isBlank() ? "master" : primary;
    }

    /**
     * 格式化SQL（仅去除首尾空白，保留字符串与注释语义）。
     *
     * @param sql SQL语句
     * @return 格式化后的SQL
     */
    public String normalizeSql(String sql) {
        if (sql != null && !sql.trim().isBlank()) {
            return sql.strip();
        }
        return sql;
    }
}
