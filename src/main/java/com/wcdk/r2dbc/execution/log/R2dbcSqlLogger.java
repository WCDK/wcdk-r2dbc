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
import java.util.regex.Pattern;

/**
 * R2DBC SQL日志记录器。
 *
 * @author WCDK
 * @version 1.0
 * @date 2026/8/5
 **/
public class R2dbcSqlLogger {

    private static final Logger log = LoggerFactory.getLogger(R2dbcSqlLogger.class);
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|token|secret|credential|certificate|cert|idcard|identity).*");
    private static final int MAX_VALUE_LENGTH = 256;
    private static final int MAX_COLLECTION_ITEMS = 20;

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
    public void logSql(ContextView contextView, String sql, Map<?, ?> parameters) {
        if (!properties.isSqlLogEnabled()) {
            return;
        }
        String dataSource = currentDataSource(contextView);
        if (parameters == null || parameters.isEmpty()) {
            log.info("=================R2DBC==========START==========");
            log.info("数据源：{}", dataSource);
            log.info("执行SQL：{}", normalizeSql(sql));

            return;
        }
        log.info("=================R2DBC==========START==========");
        log.info("数据源：{}", dataSource);
        log.info("执行SQL：{}", normalizeSql(sql));
        log.info("参数：{}", sanitizeParameters(parameters));
    }

    /**
     * 记录一次 SQL 执行实际返回或影响的行数。
     *
     * @param resultCount 返回或影响的行数
     */
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
        log.info("=========r2dbc==start==========\n" +
                        "executionId：{}\n" +
                        "excuteSQl：{}\n" +
                        "excuteParam：{}\n" +
                        "completeSql：{}\n" +
                        "result：{}\n" +
                        "=========r2dbc==end==========",
                UUID.randomUUID(), normalizeSql(sql), sanitizeParameters(parameters),
                completeSql(sql, parameters), describeResult(result));
    }

    /**
     * 将命名参数替换为 SQL 字面量，生成可复制执行的 SQL。
     * 敏感参数仍会脱敏，但会保留合法的 SQL 字面量格式。
     *
     * @param sql SQL语句
     * @param parameters 参数
     * @return 完整SQL
     */
    public String completeSql(String sql, Map<?, ?> parameters) {
        if (sql == null || sql.isBlank() || parameters == null || parameters.isEmpty()) {
            return normalizeSql(sql);
        }
        StringBuilder result = new StringBuilder(sql.length() + parameters.size() * 16);
        LexerState state = LexerState.NORMAL;
        String delimiter = null;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (state == LexerState.LINE_COMMENT) {
                result.append(ch);
                if (ch == '\n' || ch == '\r') state = LexerState.NORMAL;
                continue;
            }
            if (state == LexerState.BLOCK_COMMENT) {
                result.append(ch);
                if (ch == '*' && next == '/') {
                    result.append(next); i++; state = LexerState.NORMAL;
                }
                continue;
            }
            if (state == LexerState.POSTGRES_DOLLAR_QUOTE || state == LexerState.ORACLE_Q_QUOTE) {
                if (delimiter != null && sql.startsWith(delimiter, i)) {
                    result.append(delimiter); i += delimiter.length() - 1;
                    state = LexerState.NORMAL; delimiter = null;
                } else {
                    result.append(ch);
                }
                continue;
            }
            if (state == LexerState.SINGLE_QUOTE || state == LexerState.DOUBLE_QUOTE || state == LexerState.BACKTICK) {
                result.append(ch);
                char quote = state == LexerState.SINGLE_QUOTE ? '\'' : state == LexerState.DOUBLE_QUOTE ? '"' : '`';
                if (ch == quote && next == quote) {
                    result.append(next); i++;
                } else if (ch == quote) {
                    state = LexerState.NORMAL;
                }
                continue;
            }
            if (ch == '-' && next == '-') {
                result.append(ch).append(next); i++; state = LexerState.LINE_COMMENT; continue;
            }
            if (ch == '/' && next == '*') {
                result.append(ch).append(next); i++; state = LexerState.BLOCK_COMMENT; continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                result.append(ch);
                state = ch == '\'' ? LexerState.SINGLE_QUOTE : ch == '"' ? LexerState.DOUBLE_QUOTE : LexerState.BACKTICK;
                continue;
            }
            if (ch == '$') {
                String tag = postgresDollarDelimiter(sql, i);
                if (tag != null) {
                    result.append(tag); i += tag.length() - 1;
                    state = LexerState.POSTGRES_DOLLAR_QUOTE; delimiter = tag; continue;
                }
            }
            if ((ch == 'q' || ch == 'Q') && next == '\'' && i + 2 < sql.length()) {
                char close = oracleQuoteClose(sql.charAt(i + 2));
                if (close != 0) {
                    result.append(ch).append(next).append(sql.charAt(i + 2)); i += 2;
                    state = LexerState.ORACLE_Q_QUOTE; delimiter = String.valueOf(close) + "'"; continue;
                }
            }
            if (ch == ':' && isParameterStart(next) && (i == 0 || sql.charAt(i - 1) != ':')) {
                int end = i + 2;
                while (end < sql.length() && isParameterPart(sql.charAt(end))) end++;
                String name = sql.substring(i + 1, end);
                if (parameters.containsKey(name)) {
                    Object value = SENSITIVE_KEY.matcher(name).matches() ? "[REDACTED]" : parameters.get(name);
                    result.append(toSqlLiteral(value)); i = end - 1; continue;
                }
            }
            result.append(ch);
        }
        return normalizeSql(result.toString());
    }

    private String toSqlLiteral(Object value) {
        if (value instanceof SqlParameter parameter) value = parameter.value();
        if (value == null) return "NULL";
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

    private String postgresDollarDelimiter(String sql, int index) {
        int end = sql.indexOf('$', index + 1);
        if (end <= index) return null;
        for (int i = index + 1; i < end; i++) {
            char ch = sql.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_')) return null;
        }
        return sql.substring(index, end + 1);
    }

    private char oracleQuoteClose(char open) {
        return switch (open) {
            case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> 0;
        };
    }

    private boolean isParameterStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private boolean isParameterPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private enum LexerState {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK, LINE_COMMENT, BLOCK_COMMENT,
        POSTGRES_DOLLAR_QUOTE, ORACLE_Q_QUOTE
    }

    public Map<String, Object> sanitizeParameters(Map<?, ?> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            String name = String.valueOf(key);
            sanitized.put(name, SENSITIVE_KEY.matcher(name).matches() ? "[REDACTED]" : sanitizeValue(value));
        });
        return java.util.Collections.unmodifiableMap(sanitized);
    }

    private Object sanitizeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "[bytes:" + bytes.length + "]";
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> values = new java.util.ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count++ == MAX_COLLECTION_ITEMS) {
                    values.add("[truncated]");
                    break;
                }
                values.add(sanitizeValue(item));
            }
            return values;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            java.util.List<Object> values = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(length, MAX_COLLECTION_ITEMS); i++) {
                values.add(sanitizeValue(Array.get(value, i)));
            }
            if (length > MAX_COLLECTION_ITEMS) {
                values.add("[truncated:" + length + "]");
            }
            return values;
        }
        return truncate(String.valueOf(value));
    }

    private Object describeResult(Object result) {
        if (result instanceof Throwable error) {
            return "ERROR(" + error.getClass().getSimpleName() + ": " + truncate(error.getMessage()) + ")";
        }
        return result;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_VALUE_LENGTH) + "...[truncated]";
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
     * 格式化SQL（去除多余空格）。
     *
     * @param sql SQL语句
     * @return 格式化后的SQL
     */
    public String normalizeSql(String sql) {
        if (sql != null && !sql.trim().isBlank()) {
            return sql.strip().replaceAll("\\s+", " ");
        }
        return sql;
    }
}
