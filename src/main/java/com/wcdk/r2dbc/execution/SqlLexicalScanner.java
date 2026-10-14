package com.wcdk.r2dbc.execution;

/**
 * 定位 SQL 字符串、标识符及注释，供 XML 绑定和完整 SQL 日志共用。
 * @author wcdk
 */
public final class SqlLexicalScanner {
    private SqlLexicalScanner() { }

    /** 返回非代码片段的结束位置；当前位置为代码时返回原位置。 */
    public static int protectedEnd(String sql, int start) {
        char ch = sql.charAt(start);
        char next = start + 1 < sql.length() ? sql.charAt(start + 1) : 0;
        if ((ch == '-' && next == '-') || (ch == '#' && next != '{')) {
            int end = start;
            while (end < sql.length() && sql.charAt(end) != '\n' && sql.charAt(end) != '\r') end++;
            return end;
        }
        if (ch == '/' && next == '*') {
            int depth = 1;
            for (int end = start + 2; end < sql.length() - 1; end++) {
                if (sql.startsWith("/*", end)) { depth++; end++; }
                else if (sql.startsWith("*/", end)) {
                    if (--depth == 0) return end + 2;
                    end++;
                }
            }
            return sql.length();
        }
        if (ch == '$') {
            int tagEnd = sql.indexOf('$', start + 1);
            if (tagEnd >= 0) {
                String tag = sql.substring(start, tagEnd + 1);
                if (tag.matches("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$")) {
                    int end = sql.indexOf(tag, tagEnd + 1);
                    return end < 0 ? sql.length() : end + tag.length();
                }
            }
        }
        if ((ch == 'q' || ch == 'Q') && next == '\'' && start + 2 < sql.length()) {
            char open = sql.charAt(start + 2);
            char close = switch (open) {
                case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open;
            };
            int end = sql.indexOf(String.valueOf(close) + "'", start + 3);
            return end < 0 ? sql.length() : end + 2;
        }
        if (ch == '\'' || ch == '"' || ch == '`') {
            for (int end = start + 1; end < sql.length(); end++) {
                if (ch == '\'' && sql.charAt(end) == '\\') { end++; continue; }
                if (sql.charAt(end) == ch) {
                    if (end + 1 < sql.length() && sql.charAt(end + 1) == ch) end++;
                    else return end + 1;
                }
            }
            return sql.length();
        }
        return start;
    }
}
