package com.wcdk.r2dbc.database.mysql;

import io.r2dbc.spi.ConnectionFactory;

/**
 * MySQL R2DBC 识别工具。
 * @author wcdk
 */
final class MysqlR2dbcSupport {

    private MysqlR2dbcSupport() {
    }

    static boolean isMysql(ConnectionFactory connectionFactory) {
        if (connectionFactory == null || connectionFactory.getMetadata() == null) {
            return false;
        }
        String name = connectionFactory.getMetadata().getName();
        if (name == null) {
            return false;
        }
        String normalizedName = name.trim().toLowerCase();
        return "mysql".equals(normalizedName)
                || normalizedName.contains("mysql");
    }
}