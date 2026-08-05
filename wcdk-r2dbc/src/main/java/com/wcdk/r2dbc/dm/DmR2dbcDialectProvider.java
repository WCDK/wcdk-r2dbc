package com.wcdk.r2dbc.dm;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;

import java.util.Optional;

/**
 * 达梦 R2DBC 方言提供者。
 *
 * @author WCDK
 * @date 2026/7/20
 * @version 1.0
 **/
public class DmR2dbcDialectProvider implements DialectResolver.R2dbcDialectProvider {

    @Override
    public Optional<R2dbcDialect> getDialect(ConnectionFactory connectionFactory) {
        return DmR2dbcSupport.isDm(connectionFactory) ? Optional.of(DmR2dbcDialect.INSTANCE) : Optional.empty();
    }
}
