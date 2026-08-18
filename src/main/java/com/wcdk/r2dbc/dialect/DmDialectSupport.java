package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/***
 * 杈炬ⅵ鏁版嵁搴撴敮鎸佸伐鍏枫€?
 * @author wcdk
 */
final class DmDialectSupport {
    private DmDialectSupport() {
    }

    static boolean isDm(ConnectionFactory factory) {
        if (factory == null || factory.getMetadata() == null || factory.getMetadata().getName() == null) return false;
        String name = factory.getMetadata().getName().trim().toLowerCase();
        return name.equals("dm") || name.equals("dm database") || name.contains("dameng");
    }
}
