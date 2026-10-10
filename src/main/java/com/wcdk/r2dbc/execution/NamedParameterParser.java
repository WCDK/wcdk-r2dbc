package com.wcdk.r2dbc.execution;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Unified SQL named-parameter lexer.
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
                if (ch == '\'' && next == '\'') i++;
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
            if (ch == '?') { indexes.add(questionIndex++); continue; }
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