package com.wcdk.r2dbc.dialect;


/***
 * 达梦数据库方言。
 * @author wcdk
 */
public final class DmDatabaseDialect extends OracleDatabaseDialect {
    public static final DmDatabaseDialect INSTANCE = new DmDatabaseDialect();

    private DmDatabaseDialect() {
    }

}
