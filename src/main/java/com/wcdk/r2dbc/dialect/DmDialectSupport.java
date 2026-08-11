package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/***
 * 达梦数据库支持工具。
 * @author wcdk
 */
final class DmDialectSupport {
    private DmDialectSupport() {
    }

    static boolean isDm(ConnectionFactory factory) {
        if (factory == null || factory.getMetadata() == null || factory.getMetadata().getName() == null) return false;
        String name = factory.getMetadata().getName().toLowerCase();
        return name.equals("dm") || name.contains("dameng");
    }
}
