package com.wcdk.r2dbc.dialect;

/***
 * MySQL 数据库方言。
 * @author wcdk
 */
public final class MySqlDialect extends AbstractR2dbcDialect {
    public static final MySqlDialect INSTANCE = new MySqlDialect();

    private MySqlDialect() {
        super("mysql", "`");
    }

    @Override
    public String renderLimitOffset(Integer limit, Long offset) {
        return limit == null ? "" : offset == null ? "LIMIT " + limit : "LIMIT " + offset + ", " + limit;
    }

    @Override
    public String renderGeneratedKey(String... columns) {
        return "";
    }

    @Override
    public boolean supportsReturning() {
        return false;
    }

    @Override
    public boolean supportsUpsert() {
        return true;
    }
}
