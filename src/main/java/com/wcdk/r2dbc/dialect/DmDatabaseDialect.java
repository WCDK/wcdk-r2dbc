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

    @Override
    public boolean supports(ConnectionFactory connectionFactory) {
        return DmDialectSupport.isDm(connectionFactory);
    }
}
