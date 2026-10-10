package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/***
 * 达梦数据库方言。
 * @author wcdk
 */
public final class DmDatabaseDialect extends OracleDatabaseDialect {
    public static final DmDatabaseDialect INSTANCE = new DmDatabaseDialect();

    private DmDatabaseDialect() {
    }

    /*** 达梦独立标识用于参数转换器选择。 @author wcdk ***/
    @Override
    public DatabaseType databaseType() {
        return DatabaseType.DM;
    }

    /** DM generates identity values only when the identity column is omitted. */
    @Override
    public String emptyInsert(String table, String idColumn) {
        return "INSERT INTO " + table + " DEFAULT VALUES";
    }

    /** DM uses JDBC column names for getGeneratedKeys, rather than an appended SQL expression. */
    @Override
    public String generatedValueColumn(String rawColumn, String renderedColumn) {
        return rawColumn;
    }

    @Override
    public boolean supports(ConnectionFactory connectionFactory) {
        return DmDialectSupport.isDm(connectionFactory);
    }
}
