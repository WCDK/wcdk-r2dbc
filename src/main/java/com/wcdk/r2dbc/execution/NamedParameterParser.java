package com.wcdk.r2dbc.execution;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 统一的 SQL 命名参数词法分析器。
 *
 * @author wcdk
 */
public final class NamedParameterParser {

    private NamedParameterParser() {
    }

    public static Set<String> parse(String sql) {
        return placeholders(sql).names();
    }
    public record Placeholders(Set<String> names, Set<Integer> indexes) {}
    public static Placeholders placeholders(String sql) {
        Set<String> names = new LinkedHashSet<>();
        Set<Integer> indexes = new LinkedHashSet<>();
        int questionIndex = 0;
        int bracketDepth = 0;
        if (sql == null || sql.isEmpty()) {
            return new Placeholders(names, indexes);
        }

        LexerState state = LexerState.NORMAL;
        String delimiter = null;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (state == LexerState.LINE_COMMENT) {
                if (ch == '\n' || ch == '\r') state = LexerState.NORMAL;
                continue;
            }
            if (state == LexerState.BLOCK_COMMENT) {
                if (ch == '*' && next == '/') { state = LexerState.NORMAL; i++; }
                continue;
            }
            if (state == LexerState.POSTGRES_DOLLAR_QUOTE || state == LexerState.ORACLE_Q_QUOTE) {
                if (delimiter != null && sql.startsWith(delimiter, i)) {
                    i += delimiter.length() - 1;
                    state = LexerState.NORMAL;
                    delimiter = null;
                }
                continue;
            }
            if (state == LexerState.SINGLE_QUOTE) {
                if (ch == '\\' && next != 0) i++;
                else if (ch == '\'' && next == '\'') i++;
                else if (ch == '\'') state = LexerState.NORMAL;
                continue;
            }
            if (state == LexerState.DOUBLE_QUOTE) {
                if (ch == '"' && next == '"') i++;
                else if (ch == '"') state = LexerState.NORMAL;
                continue;
            }
            if (state == LexerState.BACKTICK) {
                if (ch == '`' && next == '`') i++;
                else if (ch == '`') state = LexerState.NORMAL;
                continue;
            }

            if (ch == '#') { state = LexerState.LINE_COMMENT; continue; }
            if (ch == '[') { bracketDepth++; continue; }
            if (ch == ']') { bracketDepth = Math.max(0, bracketDepth - 1); continue; }
            if (ch == '-' && next == '-') { state = LexerState.LINE_COMMENT; i++; continue; }
            if (ch == '/' && next == '*') { state = LexerState.BLOCK_COMMENT; i++; continue; }
            if (ch == '\'') { state = LexerState.SINGLE_QUOTE; continue; }
            if (ch == '"') { state = LexerState.DOUBLE_QUOTE; continue; }
            if (ch == '`') { state = LexerState.BACKTICK; continue; }
            if (ch == '$') {
                String tag = postgresDollarDelimiter(sql, i);
                if (tag != null) {
                    state = LexerState.POSTGRES_DOLLAR_QUOTE;
                    delimiter = tag;
                    i += tag.length() - 1;
                    continue;
                }
            }
            if (ch == 'q' && next == '\'' && i + 2 < sql.length()) {
                char close = oracleQuoteClose(sql.charAt(i + 2));
                if (close != 0) {
                    state = LexerState.ORACLE_Q_QUOTE;
                    delimiter = String.valueOf(close) + "'";
                    i += 2;
                    continue;
                }
            }
            if (ch == '?') {
                if (!isJsonOperator(sql, i)) indexes.add(questionIndex++);
                continue;
            }
            if (ch == ':' && bracketDepth > 0) continue;
            if ((ch == '$' || ch == ':') && Character.isDigit(next)
                    && !(i > 0 && sql.charAt(i - 1) == ':')) {
                int end = i + 2;
                while (end < sql.length() && Character.isDigit(sql.charAt(end))) end++;
                int ordinal = Integer.parseInt(sql.substring(i + 1, end));
                if (ordinal < 1) throw new IllegalArgumentException("SQL positional markers are one-based: " + sql);
                indexes.add(ordinal - 1);
                i = end - 1;
                continue;
            }
            if (ch != ':' || !isParameterStart(next)) continue;
            if ((i > 0 && sql.charAt(i - 1) == ':') || next == '=') continue;
            int end = i + 2;
            while (end < sql.length() && isParameterPart(sql.charAt(end))) end++;
            names.add(sql.substring(i + 1, end));
            i = end - 1;
        }
        return new Placeholders(names, indexes);
    }

    /** 在末尾注释之外追加子句，并仅移除最终语句的终止分号。 */
    public static String appendClause(String sql, String clause) {
        java.util.Objects.requireNonNull(sql, "SQL不能为空");
        int lastCode = -1;
        LexerState state = LexerState.NORMAL;
        String delimiter = null;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (state == LexerState.LINE_COMMENT) {
                if (ch == '\n' || ch == '\r') state = LexerState.NORMAL;
                continue;
            }
            if (state == LexerState.BLOCK_COMMENT) {
                if (ch == '*' && next == '/') { state = LexerState.NORMAL; i++; }
                continue;
            }
            if (state != LexerState.NORMAL && state != LexerState.LINE_COMMENT && state != LexerState.BLOCK_COMMENT) lastCode = i;
            if (state == LexerState.POSTGRES_DOLLAR_QUOTE || state == LexerState.ORACLE_Q_QUOTE) {
                if (delimiter != null && sql.startsWith(delimiter, i)) {
                    i += delimiter.length() - 1;
                    state = LexerState.NORMAL;
                    delimiter = null;
                }
                continue;
            }
            if (state == LexerState.SINGLE_QUOTE) {
                if (ch == '\\' && next != 0) i++;
                else if (ch == '\'' && next == '\'') i++;
                else if (ch == '\'') state = LexerState.NORMAL;
                continue;
            }
            if (state == LexerState.DOUBLE_QUOTE) {
                if (ch == '"' && next == '"') i++;
                else if (ch == '"') state = LexerState.NORMAL;
                continue;
            }
            if (state == LexerState.BACKTICK) {
                if (ch == '`' && next == '`') i++;
                else if (ch == '`') state = LexerState.NORMAL;
                continue;
            }

            if (ch == '#') { state = LexerState.LINE_COMMENT; continue; }
            if (ch == '-' && next == '-') { state = LexerState.LINE_COMMENT; i++; continue; }
            if (ch == '/' && next == '*') { state = LexerState.BLOCK_COMMENT; i++; continue; }
            if (!Character.isWhitespace(ch)) lastCode = i;
            if (ch == '\'') { state = LexerState.SINGLE_QUOTE; continue; }
            if (ch == '"') { state = LexerState.DOUBLE_QUOTE; continue; }
            if (ch == '`') { state = LexerState.BACKTICK; continue; }
            if (ch == '$') {
                String tag = postgresDollarDelimiter(sql, i);
                if (tag != null) {
                    state = LexerState.POSTGRES_DOLLAR_QUOTE;
                    delimiter = tag;
                    i += tag.length() - 1;
                    continue;
                }
            }
            if ((ch == 'q' || ch == 'Q') && next == '\'' && i + 2 < sql.length()) {
                char close = oracleQuoteClose(sql.charAt(i + 2));
                if (close != 0) {
                    state = LexerState.ORACLE_Q_QUOTE;
                    delimiter = String.valueOf(close) + "'";
                    i += 2;
                    continue;
                }
            }
        }
        if (lastCode >= 0 && sql.charAt(lastCode) == ';'
                && (state == LexerState.NORMAL || state == LexerState.LINE_COMMENT)) {
            sql = sql.substring(0,lastCode) + sql.substring(lastCode+1);
        }
        return sql.stripTrailing() + (state == LexerState.LINE_COMMENT ? "\n" : " ") + clause.strip();
    }

    /** ?| / ?& 总是运算符；裸 ? 仅在左右均为 SQL 操作数时视为 JSONB 运算符。 */
    public static boolean isJsonOperator(String sql, int index) {
        if (index + 1 < sql.length() && (sql.charAt(index + 1) == '|' || sql.charAt(index + 1) == '&')) return true;
        int left = index - 1;
        while (left >= 0 && Character.isWhitespace(sql.charAt(left))) left--;
        int right = index + 1;
        while (right < sql.length() && Character.isWhitespace(sql.charAt(right))) right++;
        if (left < 0 || right >= sql.length()) return false;
        char previous = sql.charAt(left);
        if (!(Character.isLetterOrDigit(previous) || previous == '_' || previous == '\''
                || previous == '"' || previous == ')' || previous == ']' || previous == '$')) return false;
        if (Character.isLetterOrDigit(previous) || previous == '_') {
            int start = left;
            while (start > 0 && (Character.isLetterOrDigit(sql.charAt(start - 1)) || sql.charAt(start - 1) == '_')) start--;
            String token = sql.substring(start, left + 1).toUpperCase(java.util.Locale.ROOT);
            if (Set.of("SELECT", "WHERE", "AND", "OR", "NOT", "VALUES", "SET", "BY", "AS", "FROM",
                    "RETURNING", "WHEN", "THEN", "ELSE", "LIMIT", "OFFSET", "DISTINCT", "CASE", "ON", "IN", "IS",
                    "HAVING", "BETWEEN", "LIKE", "ILIKE", "SIMILAR", "TO", "ESCAPE", "FIRST", "NEXT", "TOP",
                    "FETCH", "EXISTS", "ALL", "ANY", "SOME", "USING", "INTO")
                    .contains(token)) return false;
        }
        char following = sql.charAt(right);
        return following == '\'' || following == '"' || following == ':' || following == '$'
                || following == '?' || following == '(' || following == '[' || Character.isLetterOrDigit(following);
    }

    private static String postgresDollarDelimiter(String sql, int index) {
        int end = sql.indexOf('$', index + 1);
        if (end <= index) return null;
        if (end > index + 1 && !isParameterStart(sql.charAt(index + 1))) return null;
        for (int i = index + 1; i < end; i++) {
            char ch = sql.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_')) return null;
        }
        return sql.substring(index, end + 1);
    }

    private static char oracleQuoteClose(char open) {
        return switch (open) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> 0;
        };
    }

    private static boolean isParameterStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isParameterPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private enum LexerState {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK, LINE_COMMENT, BLOCK_COMMENT,
        POSTGRES_DOLLAR_QUOTE, ORACLE_Q_QUOTE
    }
}
