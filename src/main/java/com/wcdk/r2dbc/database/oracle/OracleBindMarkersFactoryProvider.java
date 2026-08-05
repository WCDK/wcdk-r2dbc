package com.wcdk.r2dbc.database.oracle;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.dialect.OracleDialect;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;
import org.springframework.r2dbc.core.binding.BindMarkersFactoryResolver;

/**
 * Oracle R2DBC 绑定标记提供者。
 */
public class OracleBindMarkersFactoryProvider implements BindMarkersFactoryResolver.BindMarkerFactoryProvider {

    @Override
    public BindMarkersFactory getBindMarkers(ConnectionFactory connectionFactory) {
        return OracleR2dbcSupport.isOracle(connectionFactory)
                ? OracleDialect.INSTANCE.getBindMarkersFactory()
                : null;
    }
}