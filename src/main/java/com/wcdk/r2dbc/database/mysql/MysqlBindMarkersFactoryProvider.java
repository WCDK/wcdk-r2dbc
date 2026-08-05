package com.wcdk.r2dbc.database.mysql;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.dialect.MySqlDialect;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;
import org.springframework.r2dbc.core.binding.BindMarkersFactoryResolver;

/**
 * MySQL R2DBC 绑定标记提供者。
 */
public class MysqlBindMarkersFactoryProvider implements BindMarkersFactoryResolver.BindMarkerFactoryProvider {

    @Override
    public BindMarkersFactory getBindMarkers(ConnectionFactory connectionFactory) {
        return MysqlR2dbcSupport.isMysql(connectionFactory)
                ? MySqlDialect.INSTANCE.getBindMarkersFactory()
                : null;
    }
}