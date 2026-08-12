package com.wcdk.r2dbc.execution;

import java.util.LinkedHashSet;
import java.util.Set;

/***
 * SQL named parameter parser.
 * @author wcdk
***/
public final class NamedParameterParser {
    private NamedParameterParser() {
    }

    public static Set<String> parse(String sql) {
        Set<String> names = new LinkedHashSet<>();
        if (sql == null || sql.isEmpty()) return names;
        boolean single = false, quoted = false, backtick = false, line = false, block = false;
        String dollar = null;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (line) { if (ch == '\n' || ch == '\r') line = false; continue; }
            if (block) { if (ch == '*' && next == '/') { block = false; i++; } continue; }
            if (dollar != null) {
                if (sql.startsWith(dollar, i)) { i += dollar.length() - 1; dollar = null; }
                continue;
            }
            if (!single && !quoted && !backtick) {
                if (ch == '-' && next == '-') { line = true; i++; continue; }
                if (ch == '/' && next == '*') { block = true; i++; continue; }
                if (ch == '$') {
                    int end = sql.indexOf('$', i + 1);
                    if (end > i && sql.substring(i + 1, end).chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_')) {
                        dollar = sql.substring(i, end + 1); i = end; continue;
                    }
                }
            }
            if (ch == '\'' && !quoted && !backtick) { if (single && next == '\'') i++; else single = !single; continue; }
            if (ch == '"' && !single && !backtick) { quoted = !quoted; continue; }
            if (ch == '`' && !single && !quoted) { backtick = !backtick; continue; }
            if (single || quoted || backtick || ch != ':' || !start(next)) continue;
            if ((i > 0 && sql.charAt(i - 1) == ':') || next == '=') continue;
            int end = i + 2;
            while (end < sql.length() && part(sql.charAt(end))) end++;
            names.add(sql.substring(i + 1, end)); i = end - 1;
        }
        return names;
    }

    private static boolean start(char ch) { return Character.isLetter(ch) || ch == '_'; }
    private static boolean part(char ch) { return Character.isLetterOrDigit(ch) || ch == '_'; }
}