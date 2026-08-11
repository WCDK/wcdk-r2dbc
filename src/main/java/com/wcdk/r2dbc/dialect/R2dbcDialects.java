package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/***
 * R2DBC 方言注册与解析工具。
 * @author wcdk
 */
public final class R2dbcDialects {
    private R2dbcDialects() {
    }

    public static R2dbcDialect get(ConnectionFactory factory) {
        if (DmDialect.INSTANCE.supports(factory)) return DmDialect.INSTANCE;
        if (PostgreSqlDialect.INSTANCE.supports(factory)) return PostgreSqlDialect.INSTANCE;
        if (MySqlDialect.INSTANCE.supports(factory)) return MySqlDialect.INSTANCE;
        if (OracleDialect.INSTANCE.supports(factory)) return OracleDialect.INSTANCE;
        throw new IllegalStateException("Unsupported R2DBC database: " + (factory == null || factory.getMetadata() == null ? "unknown" : factory.getMetadata().getName()));
    }
}
