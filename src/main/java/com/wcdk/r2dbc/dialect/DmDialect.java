package com.wcdk.r2dbc.dialect;

/***
 * 达梦数据库方言。
 * @author wcdk
 */
public final class DmDialect extends OracleDialect {
    public static final DmDialect INSTANCE = new DmDialect();

    private DmDialect() {
    }
}
