package com.wcdk.r2dbc.repository.plan;

import java.util.Map;

/***
 * SQL 绑定结果。
 * @author wcdk
 **/
public record BoundSql(String sql, Map<String, Object> parameters) {
    public BoundSql {
        parameters = Map.copyOf(parameters);
    }
}