package com.wcdk.r2dbc.database.postgresql;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;

import java.util.Optional;

/**
 * PostgreSQL R2DBC 方言提供者。
 */
public class PostgresR2dbcDialectProvider implements DialectResolver.R2dbcDialectProvider {

    @Override
    public Optional<R2dbcDialect> getDialect(ConnectionFactory connectionFactory) {
        return PostgresR2dbcSupport.isPostgres(connectionFactory)
                ? Optional.of(PostgresDialect.INSTANCE)
                : Optional.empty();
    }
}