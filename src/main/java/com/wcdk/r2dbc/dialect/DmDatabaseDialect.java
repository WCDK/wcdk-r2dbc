package com.wcdk.r2dbc.dialect;


/**
 * 达梦数据库方言。
 * @author wcdk
 */
public final class DmDatabaseDialect extends OracleDatabaseDialect {
    public static final DmDatabaseDialect INSTANCE = new DmDatabaseDialect();

    private DmDatabaseDialect() {
    }

    /**
     * 达梦独立标识用于参数转换器选择。
     * @author wcdk
     */
    @Override
    public DatabaseType databaseType() {
        return DatabaseType.DM;
    }

    /** 达梦仅在插入语句省略自增列时生成该列的值。 */
    @Override
    public String emptyInsert(String table, String idColumn) {
        return "INSERT INTO " + table + " DEFAULT VALUES";
    }

    /** 达梦通过 JDBC 列名获取生成的主键，不在 SQL 尾部追加表达式。 */
    @Override
    public String generatedValueColumn(String rawColumn, String renderedColumn) {
        return rawColumn;
    }

}
