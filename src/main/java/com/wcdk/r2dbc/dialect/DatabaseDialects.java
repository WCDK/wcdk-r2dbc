package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/***
 * R2DBC 方言注册与解析工具。
 * @author wcdk
 */
public final class DatabaseDialects {
    private DatabaseDialects() {
    }

    public static DatabaseDialect get(ConnectionFactory factory) {
        if (DmDatabaseDialect.INSTANCE.supports(factory)) return DmDatabaseDialect.INSTANCE;
        if (PostgreSqlDatabaseDialect.INSTANCE.supports(factory)) return PostgreSqlDatabaseDialect.INSTANCE;
        if (MySqlDatabaseDialect.INSTANCE.supports(factory)) return MySqlDatabaseDialect.INSTANCE;
        if (OracleDatabaseDialect.INSTANCE.supports(factory)) return OracleDatabaseDialect.INSTANCE;
        throw new IllegalStateException("不支持的R2DBC数据库：" + (factory == null || factory.getMetadata() == null ? "unknown" : factory.getMetadata().getName()));
    }
}
