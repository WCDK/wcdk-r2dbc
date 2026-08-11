package com.wcdk.r2dbc.database.mysql;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.MySqlDialect;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;

import java.util.Optional;

/**
 * MySQL R2DBC 方言提供者。
 * @author wcdk
 */
public class MysqlR2dbcDialectProvider implements DialectResolver.R2dbcDialectProvider {

    @Override
    public Optional<R2dbcDialect> getDialect(ConnectionFactory connectionFactory) {
        return MysqlR2dbcSupport.isMysql(connectionFactory)
                ? Optional.of(MySqlDialect.INSTANCE)
                : Optional.empty();
    }
}