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
 */
public class R2dbcSqlLogger {

    private static final Logger log = LoggerFactory.getLogger(R2dbcSqlLogger.class);
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|token|secret|credential|certificate|cert|idcard|identity).*");
    private static final Pattern PRIVATE_VALUE = Pattern.compile(
            "(?<!\\d)(?:1[3-9]\\d{9}|\\d{17}[\\dXx]|\\d{15})(?![\\dXx])");
    private static final Pattern CREDENTIAL_TEXT = Pattern.compile(
            "(?i)((?:password|passwd|pwd|token|secret|credential)[a-z0-9_-]*\\s*[:=]\\s*)[^\\s,:;$?'\"][^\\s,;'\"]*");
    private static final Pattern CREDENTIAL_QUOTED = Pattern.compile(
            "(?i)((?:password|passwd|pwd|token|secret|credential)[a-z0-9_-]*\\s*[:=]\\s*)(?:'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\")");
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
            log.info("执行SQL：{}", sanitizeText(normalizeSql(sql), parameters));

            return;
        }
        log.info("=================R2DBC==========START==========");
        log.info("数据源：{}", dataSource);
        log.info("执行SQL：{}", sanitizeText(normalizeSql(sql), parameters));
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
        logExecution(UUID.randomUUID().toString(), sql, parameters, result);
    }

    public void logExecution(ContextView context, String sql, Map<?, ?> parameters, Object result) {
        var execution = context.<com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext>getOrDefault(
                com.wcdk.r2dbc.execution.SqlLifecycleExecutor.EXECUTION_CONTEXT_KEY, null);
        logExecution(execution == null ? UUID.randomUUID().toString() : execution.getExecutionId(), sql, parameters, result);
    }

    public void logExecution(String executionId, String sql, Map<?, ?> parameters, Object result) {
        if (!properties.isSqlLogEnabled()) return;
        log.info("R2DBC executionId={} SQL={} parameters={} result={}", executionId,
                sanitizeText(normalizeSql(sql), parameters), sanitizeParameters(parameters),
                result instanceof Throwable error ? error.getClass().getSimpleName() + ": " + sanitizeText(error.getMessage(), parameters) : result);
        if (properties.isSqlLogCompleteEnabled()) {
            log.info("R2DBC executionId={} completeSql={}", executionId, completeSql(sql, parameters));
        }
    }

    public boolean isEnabled() { return properties.isSqlLogEnabled(); }

    public String sanitizeText(String text, Map<?, ?> parameters) {
        if (text == null) return null;
        String safe = text;
        if (parameters != null) safe = redactKnown(safe, parameters, false, 0);
        safe = CREDENTIAL_QUOTED.matcher(safe).replaceAll("$1'[REDACTED]'");
        safe = CREDENTIAL_TEXT.matcher(safe).replaceAll("$1[REDACTED]");
        return truncate(PRIVATE_VALUE.matcher(safe).replaceAll("[REDACTED]"));
    }

    private String redactKnown(String text, Object value, boolean sensitive, int depth) {
        if (depth >= 8) return "[REDACTED:deep-context]";
        if (value instanceof SqlParameter parameter) {
            sensitive |= parameter.name() != null && isSensitive(parameter.name());
            value = parameter.value();
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 1000) return "[REDACTED:large-context]";
            for (var entry : map.entrySet()) text = redactKnown(text, entry.getValue(), sensitive || isSensitive(String.valueOf(entry.getKey())), depth + 1);
        } else if (value instanceof Iterable<?> values) {
            int count=0;
            for (Object item : values) {
                if (++count > 1000) return "[REDACTED:large-context]";
                text = redactKnown(text,item,sensitive,depth+1);
            }
        } else if (value != null && value.getClass().isArray()) {
            int size=Array.getLength(value);
            if(size>1000)return "[REDACTED:large-context]";
            for(int i=0;i<size;i++)text=redactKnown(text,Array.get(value,i),sensitive,depth+1);
        } else if (sensitive && value != null && !String.valueOf(value).isEmpty()) {
            String raw=String.valueOf(value);
            text=text.replace(raw.replace("'","''"),"[REDACTED]").replace(raw,"[REDACTED]");
        }
        return text;
    }

    private boolean isSensitive(String name) {
        String normalized = name.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (SENSITIVE_KEY.matcher(name).matches()) return true;
        return properties.getSensitiveColumns() != null && properties.getSensitiveColumns().stream()
                .anyMatch(column -> column != null && normalized.equals(column.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT)));
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
            return sanitizeText(normalizeSql(sql), parameters);
        }
        Map<String, Object> sanitized = sanitizeParameters(parameters);
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
                if (ch == '\\' && next != 0) { result.append(next); i++; continue; }
                char quote = state == LexerState.SINGLE_QUOTE ? '\'' : state == LexerState.DOUBLE_QUOTE ? '"' : '`';
                if (ch == quote && next == quote) {
                    result.append(next); i++;
                } else if (ch == quote) {
                    state = LexerState.NORMAL;
                }
                continue;
            }
            if (ch == '#') { result.append(ch); state = LexerState.LINE_COMMENT; continue; }
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
                    Object value = sanitized.get(name);
                    result.append(toSqlLiteral(value)); i = end - 1; continue;
                }
            }
            result.append(ch);
        }
        return sanitizeText(normalizeSql(result.toString()), parameters);
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
            sanitized.put(name, isSensitive(name) ? "[REDACTED]" : sanitizeValue(value));
        });
        return java.util.Collections.unmodifiableMap(sanitized);
    }

    private Object sanitizeValue(Object value) { return sanitizeValue(value, 0); }

    private Object sanitizeValue(Object value, int depth) {
        if (depth >= 8) return "[depth-limit]";
        if (value instanceof SqlParameter parameter) {
            if (parameter.name() != null && isSensitive(parameter.name())) return "[REDACTED]";
            value = parameter.value();
        }
        if ((value instanceof CharSequence || value instanceof Number) && PRIVATE_VALUE.matcher(String.valueOf(value)).find()) return "[REDACTED]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            int count = 0;
            for (var entry : map.entrySet()) {
                if (count++ >= MAX_COLLECTION_ITEMS) { safe.put("[truncated]", true); break; }
                String name = String.valueOf(entry.getKey());
                safe.put(name, isSensitive(name) ? "[REDACTED]" : sanitizeValue(entry.getValue(), depth + 1));
            }
            return safe;
        }
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
                values.add(sanitizeValue(item, depth + 1));
            }
            return values;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            java.util.List<Object> values = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(length, MAX_COLLECTION_ITEMS); i++) {
                values.add(sanitizeValue(Array.get(value, i), depth + 1));
            }
            if (length > MAX_COLLECTION_ITEMS) {
                values.add("[truncated:" + length + "]");
            }
            return values;
        }
        return sanitizeText(String.valueOf(value), Map.of());
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
