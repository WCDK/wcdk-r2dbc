package com.wcdk.r2dbc.repository.plan;

import java.util.Map;

/***
 * 静态 SQL 来源。
 * @author wcdk
 **/
public record StaticSqlSource(String sql) implements SqlSource {

    @Override
    public BoundSql getBoundSql(Object[] arguments) {
        return new BoundSql(sql, Map.of());
    }
}