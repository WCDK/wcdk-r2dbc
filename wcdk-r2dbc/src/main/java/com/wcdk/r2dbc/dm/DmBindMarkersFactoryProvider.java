package com.wcdk.r2dbc.dm;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;
import org.springframework.r2dbc.core.binding.BindMarkersFactoryResolver;

/**
 * 达梦 R2DBC 绑定标记提供者。
 *
 * @author WCDK
 * @date 2026/7/20
 * @version 1.0
 **/
public class DmBindMarkersFactoryProvider implements BindMarkersFactoryResolver.BindMarkerFactoryProvider {

    @Override
    public BindMarkersFactory getBindMarkers(ConnectionFactory connectionFactory) {
        return DmR2dbcSupport.isDm(connectionFactory) ? DmR2dbcDialect.INSTANCE.getBindMarkersFactory() : null;
    }
}
