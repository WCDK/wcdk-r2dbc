package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/**
 * R2DBC 方言注册与解析工具。
 * @author wcdk
 */
public final class DatabaseDialects {
    private static final java.util.concurrent.ConcurrentHashMap<ConnectionFactory, DatabaseDialect> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private DatabaseDialects() {
    }

    public static DatabaseDialect get(ConnectionFactory factory) {
        if (factory == null) throw new IllegalStateException("不支持的R2DBC数据库：unknown");
        return CACHE.computeIfAbsent(factory, DatabaseDialects::resolve);
    }

    private static DatabaseDialect resolve(ConnectionFactory factory) {
        var metadata = factory.getMetadata();
        String name = metadata == null ? null : metadata.getName();
        return switch (DatabaseType.from(name)) {
            case DM -> DmDatabaseDialect.INSTANCE;
            case POSTGRESQL -> PostgreSqlDatabaseDialect.INSTANCE;
            case MYSQL -> MySqlDatabaseDialect.INSTANCE;
            case ORACLE -> OracleDatabaseDialect.INSTANCE;
            case UNKNOWN -> throw new IllegalStateException("不支持的R2DBC数据库：" + name);
        };
    }
}
