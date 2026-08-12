package com.wcdk.r2dbc.query.sql;

import java.util.Map;

/***
 * SQL 谓词渲染结果。
 * @author wcdk
 */
public record RenderedPredicate(String sql, Map<String, Object> bindings) {
    public static RenderedPredicate empty() {
        return new RenderedPredicate("", Map.of());
    }
}
