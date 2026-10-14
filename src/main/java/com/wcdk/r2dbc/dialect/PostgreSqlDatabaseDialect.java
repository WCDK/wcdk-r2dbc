package com.wcdk.r2dbc.dialect;

/**
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
    public String renderOffsetOnly(long offset) {
        if (offset < 0) throw new IllegalArgumentException("查询偏移量不能小于 0");
        return "OFFSET " + offset;
    }

    @Override
    public boolean supportsReturning() {
        return true;
    }

}
