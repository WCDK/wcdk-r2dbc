package com.wcdk.r2dbc.database.postgresql;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;
import org.springframework.r2dbc.core.binding.BindMarkersFactoryResolver;

/**
 * PostgreSQL R2DBC 绑定标记提供者。
 * @author wcdk
 */
public class PostgresBindMarkersFactoryProvider implements BindMarkersFactoryResolver.BindMarkerFactoryProvider {

    @Override
    public BindMarkersFactory getBindMarkers(ConnectionFactory connectionFactory) {
        return PostgresR2dbcSupport.isPostgres(connectionFactory)
                ? PostgresDialect.INSTANCE.getBindMarkersFactory()
                : null;
    }
}