package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

import java.util.Objects;

/***
 * R2DBC 方言抽象基类。
 * @author wcdk
 */
abstract class AbstractDatabaseDialect implements DatabaseDialect {
    private final String databaseName;
    private final String quote;

    AbstractDatabaseDialect(String databaseName, String quote) {
        this.databaseName = databaseName;
        this.quote = quote;
    }

    @Override
    public String quote(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isBlank()) throw new IllegalArgumentException("标识符不能为空");
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.from(databaseName);
    }

    @Override
    public String pagination(String sql, long offset, long limit) {
        String clause = renderLimitOffset((int) limit, offset);
        return clause == null || clause.isBlank() ? sql : sql + " " + clause;
    }

    @Override
    public boolean supportsSavepoint() {
        return true;
    }

    @Override
    public String renderGeneratedKey(String... columns) {
        if (!supportsReturning()) return "";
        return columns == null || columns.length == 0 ? " RETURNING *" : " RETURNING " + String.join(", ", columns);
    }

    @Override
    public String renderBoolean(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    @Override
    public String renderCurrentTimestamp() {
        return "CURRENT_TIMESTAMP";
    }

    @Override
    public boolean supports(ConnectionFactory factory) {
        if (factory == null || factory.getMetadata() == null || factory.getMetadata().getName() == null) return false;
        return factory.getMetadata().getName().toLowerCase().contains(databaseName);
    }
}
