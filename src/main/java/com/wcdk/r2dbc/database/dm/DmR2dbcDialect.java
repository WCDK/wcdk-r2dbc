package com.wcdk.r2dbc.database.dm;

import org.springframework.data.r2dbc.dialect.OracleDialect;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;

/**
 * 达梦 R2DBC 方言。
 *
 * @author WCDK
 * @date 2026/7/20
 * @version 1.0
 **/
public class DmR2dbcDialect extends OracleDialect {

    public static final DmR2dbcDialect INSTANCE = new DmR2dbcDialect();

    private static final BindMarkersFactory BIND_MARKERS = BindMarkersFactory.anonymous("?");

    private DmR2dbcDialect() {
    }

    @Override
    public BindMarkersFactory getBindMarkersFactory() {
        return BIND_MARKERS;
    }
}
