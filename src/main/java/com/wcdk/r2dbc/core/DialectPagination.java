package com.wcdk.r2dbc.core;

import org.springframework.data.r2dbc.dialect.R2dbcDialect;

/**
 * R2DBC 方言分页 SQL 工具。
 *
 * @author WCDK
 **/
final class DialectPagination {

    private DialectPagination() {
    }

    static String render(R2dbcDialect dialect, long limit, Long offset) {
        String clause = offset == null
                ? dialect.limit().getLimit(limit)
                : dialect.limit().getLimitOffset(limit, offset);
        return clause.isBlank() ? "" : " " + clause;
    }
}
