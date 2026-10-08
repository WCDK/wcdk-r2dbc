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

    @Override
    public boolean supports(ConnectionFactory connectionFactory) {
        return DmDialectSupport.isDm(connectionFactory);
    }
}
