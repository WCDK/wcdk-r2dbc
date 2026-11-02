package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * R2DBC 方言注册与解析工具。
 * @author wcdk
 */
public final class DatabaseDialects {
    /**
     * Connection factories belong to an application context. Weak keys prevent this
     * process-wide cache from retaining pools and their class loaders after shutdown.
     */
    private static final Map<ConnectionFactory, DatabaseDialect> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private DatabaseDialects() {
    }

    public static DatabaseDialect get(ConnectionFactory factory) {
        if (factory == null) throw new IllegalStateException("不支持的R2DBC数据库：unknown");
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(factory, DatabaseDialects::resolve);
        }
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
