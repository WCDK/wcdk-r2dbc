package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/***
 * R2DBC 数据库方言接口。
 * @author wcdk
 */
public interface R2dbcDialect {
    String quoteIdentifier(String identifier);

    String renderLimitOffset(Integer limit, Long offset);

    String renderGeneratedKey(String... columns);

    String renderBoolean(boolean value);

    String renderCurrentTimestamp();

    boolean supportsReturning();

    boolean supportsUpsert();

    boolean supports(ConnectionFactory connectionFactory);
}
