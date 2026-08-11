package com.wcdk.r2dbc.dialect;

/***
 * Oracle 数据库方言。
 * @author wcdk
 */
public class OracleDialect extends AbstractR2dbcDialect {
    public static final OracleDialect INSTANCE = new OracleDialect();

    protected OracleDialect() {
        super("oracle", "\"");
    }

    @Override
    public String renderLimitOffset(Integer limit, Long offset) {
        if (limit == null) return "";
        return offset == null ? "FETCH FIRST " + limit + " ROWS ONLY" : "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
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
