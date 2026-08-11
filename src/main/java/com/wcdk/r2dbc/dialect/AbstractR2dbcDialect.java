package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

import java.util.Objects;

/***
 * R2DBC 方言抽象基类。
 * @author wcdk
 */
abstract class AbstractR2dbcDialect implements R2dbcDialect {
    private final String databaseName;
    private final String quote;

    AbstractR2dbcDialect(String databaseName, String quote) {
        this.databaseName = databaseName;
        this.quote = quote;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isBlank()) throw new IllegalArgumentException("identifier cannot be blank");
        return quote + identifier.replace(quote, quote + quote) + quote;
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
