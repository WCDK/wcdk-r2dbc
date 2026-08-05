package com.wcdk.r2dbc.database.postgresql;

import io.r2dbc.spi.ConnectionFactory;

/**
 * PostgreSQL R2DBC 识别工具。
 */
final class PostgresR2dbcSupport {

    private PostgresR2dbcSupport() {
    }

    static boolean isPostgres(ConnectionFactory connectionFactory) {
        if (connectionFactory == null || connectionFactory.getMetadata() == null) {
            return false;
        }
        String name = connectionFactory.getMetadata().getName();
        if (name == null) {
            return false;
        }
        String normalizedName = name.trim().toLowerCase();
        return "postgresql".equals(normalizedName)
                || "postgres".equals(normalizedName)
                || normalizedName.contains("postgresql")
                || normalizedName.contains("postgres");
    }
}