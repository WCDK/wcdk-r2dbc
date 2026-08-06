package com.wcdk.r2dbc.core;

import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.OracleDialect;
import org.springframework.data.r2dbc.dialect.PostgresDialect;

import static org.assertj.core.api.Assertions.assertThat;

class DialectPaginationTests {

    @Test
    void rendersPostgresPagination() {
        assertThat(DialectPagination.render(PostgresDialect.INSTANCE, 10, 20L))
                .containsIgnoringCase("limit 10")
                .containsIgnoringCase("offset 20");
    }

    @Test
    void rendersOraclePaginationWithoutLimitKeyword() {
        assertThat(DialectPagination.render(OracleDialect.INSTANCE, 10, 20L))
                .containsIgnoringCase("offset 20")
                .containsIgnoringCase("fetch")
                .doesNotContainIgnoringCase("limit");
    }
}
