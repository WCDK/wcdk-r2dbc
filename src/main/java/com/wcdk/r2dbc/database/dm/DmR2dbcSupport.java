package com.wcdk.r2dbc.database.dm;

import io.r2dbc.spi.ConnectionFactory;

/**
 * 达梦 R2DBC 识别工具。
 *
 * @author WCDK
 * @date 2026/7/20
 * @version 1.0
 **/
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
        String normalizedName = name.trim().toLowerCase();
        return "dm".equals(normalizedName)
                || "dm database".equals(normalizedName)
                || normalizedName.contains("dameng");
    }
}
