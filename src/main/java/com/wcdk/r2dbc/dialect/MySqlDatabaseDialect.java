package com.wcdk.r2dbc.dialect;

/**
 * MySQL 数据库方言。
 * @author wcdk
 */
public final class MySqlDatabaseDialect extends AbstractDatabaseDialect {
    public static final MySqlDatabaseDialect INSTANCE = new MySqlDatabaseDialect();

    private MySqlDatabaseDialect() {
        super("mysql", "`");
    }

    @Override
    public String renderLimitOffset(Integer limit, Long offset) {
        return limit == null ? "" : offset == null ? "LIMIT " + limit : "LIMIT " + offset + ", " + limit;
    }

    @Override
    public String renderOffsetOnly(long offset) {
        if (offset < 0) throw new IllegalArgumentException("查询偏移量不能小于 0");
        return "LIMIT 18446744073709551615 OFFSET " + offset;
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
    public GeneratedKeyStrategy generatedKeyStrategy() {
        return GeneratedKeyStrategy.DRIVER_GENERATED_KEYS;
    }

    @Override
    public String emptyInsert(String table, String idColumn) {
        return "INSERT INTO " + table + " () VALUES ()";
    }

}
