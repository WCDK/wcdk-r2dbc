package com.wcdk.r2dbc.dialect;

/***
 * Oracle 数据库方言。
 * @author wcdk
 */
public class OracleDatabaseDialect extends AbstractDatabaseDialect {
    public static final OracleDatabaseDialect INSTANCE = new OracleDatabaseDialect();

    protected OracleDatabaseDialect() {
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
    public String generatedValueColumn(String rawColumn, String renderedColumn) {
        return renderedColumn;
    }

    @Override
    public boolean supportsReturning() {
        return false;
    }

    @Override
    public boolean supportsUpsert() {
        return false;
    }

    /*** 驱动通过 RETURNING INTO 返回指定列，不支持 PostgreSQL 式 RETURNING 子句。 @author wcdk ***/
    @Override
    public GeneratedKeyStrategy generatedKeyStrategy() {
        return GeneratedKeyStrategy.RETURNING;
    }

    @Override
    public String emptyInsert(String table, String idColumn) {
        if (idColumn == null) {
            throw new IllegalArgumentException("Oracle/达梦空字段插入需要生成主键列");
        }
        return "INSERT INTO " + table + " (" + idColumn + ") VALUES (DEFAULT)";
    }

    @Override
    public String existsResult(String query) {
        return super.existsResult(query) + " FROM DUAL";
    }
}
