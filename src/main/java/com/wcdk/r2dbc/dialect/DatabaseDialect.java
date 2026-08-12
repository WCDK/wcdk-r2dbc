package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/***
 * R2DBC 数据库方言接口。
 * @author wcdk
 */
public interface DatabaseDialect {
    DatabaseType databaseType();

    String quote(String identifier);

    default String quoteIdentifier(String identifier) {
        return quote(identifier);
    }

    String pagination(String sql, long offset, long limit);

    String renderLimitOffset(Integer limit, Long offset);

    String renderGeneratedKey(String... columns);

    String renderBoolean(boolean value);

    String renderCurrentTimestamp();

    default Object normalizeParameterValue(Object value) {
        return value;
    }

    default boolean supportsSavepoint() {
        return false;
    }

    default GeneratedKeyStrategy generatedKeyStrategy() {
        return supportsReturning() ? GeneratedKeyStrategy.RETURNING : GeneratedKeyStrategy.NONE;
    }

    boolean supportsReturning();

    boolean supportsUpsert();

    boolean supports(ConnectionFactory connectionFactory);
}
