package com.wcdk.r2dbc.database.oracle;

import io.r2dbc.spi.ConnectionFactory;

/**
 * Oracle R2DBC 识别工具。
 * @author wcdk
 */
final class OracleR2dbcSupport {

    private OracleR2dbcSupport() {
    }

    static boolean isOracle(ConnectionFactory connectionFactory) {
        if (connectionFactory == null || connectionFactory.getMetadata() == null) {
            return false;
        }
        String name = connectionFactory.getMetadata().getName();
        if (name == null) {
            return false;
        }
        String normalizedName = name.trim().toLowerCase();
        return "oracle".equals(normalizedName)
                || normalizedName.contains("oracle");
    }
}