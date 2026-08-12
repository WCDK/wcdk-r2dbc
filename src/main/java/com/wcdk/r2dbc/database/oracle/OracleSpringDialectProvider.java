package com.wcdk.r2dbc.database.oracle;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.OracleDialect;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;

import java.util.Optional;

/**
 * Oracle R2DBC 方言提供者。
 * @author wcdk
 */
public class OracleSpringDialectProvider implements DialectResolver.R2dbcDialectProvider {

    @Override
    public Optional<R2dbcDialect> getDialect(ConnectionFactory connectionFactory) {
        return OracleR2dbcSupport.isOracle(connectionFactory)
                ? Optional.of(OracleDialect.INSTANCE)
                : Optional.empty();
    }
}