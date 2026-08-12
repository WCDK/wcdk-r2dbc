package com.wcdk.r2dbc.dialect;

/***
 * PostgreSQL 数据库方言。
 * @author wcdk
 */
public final class PostgreSqlDatabaseDialect extends AbstractDatabaseDialect {
    public static final PostgreSqlDatabaseDialect INSTANCE = new PostgreSqlDatabaseDialect();

    private PostgreSqlDatabaseDialect() {
        super("postgres", "\"");
    }

    @Override
    public String renderLimitOffset(Integer limit, Long offset) {
        return limit == null ? "" : offset == null ? "LIMIT " + limit : "LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public boolean supportsReturning() {
        return true;
    }

    @Override
    public boolean supportsUpsert() {
        return true;
    }
}
