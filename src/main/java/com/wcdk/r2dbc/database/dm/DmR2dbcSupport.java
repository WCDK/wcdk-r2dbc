package com.wcdk.r2dbc.database.dm;

import io.r2dbc.spi.ConnectionFactory;

/**
 * 达梦 R2DBC 识别工具。
 *
 * @author WCDK
 *
 * @version 1.0
 */
final class DmR2dbcSupport {

    private DmR2dbcSupport() {
    }

    static boolean isDm(ConnectionFactory connectionFactory) {
        if (connectionFactory == null || connectionFactory.getMetadata() == null) {
            return false;
        }
        String name = connectionFactory.getMetadata().getName();
        if (name == null) {
            return false;
        }
        return com.wcdk.r2dbc.dialect.DatabaseType.from(name) == com.wcdk.r2dbc.dialect.DatabaseType.DM;
    }
}
