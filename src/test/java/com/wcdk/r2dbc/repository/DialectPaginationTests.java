package com.wcdk.r2dbc.repository;

import org.junit.jupiter.api.Test;
import com.wcdk.r2dbc.dialect.OracleDatabaseDialect;
import com.wcdk.r2dbc.dialect.PostgreSqlDatabaseDialect;

import static org.assertj.core.api.Assertions.assertThat;

class DialectPaginationTests {

    @Test
    void rendersPostgresPagination() {
        assertThat(DialectPagination.render(PostgreSqlDatabaseDialect.INSTANCE, 10, 20L))
                .containsIgnoringCase("limit 10")
                .containsIgnoringCase("offset 20");
    }

    @Test
    void rendersOraclePaginationWithoutLimitKeyword() {
        assertThat(DialectPagination.render(OracleDatabaseDialect.INSTANCE, 10, 20L))
                .containsIgnoringCase("offset 20")
                .containsIgnoringCase("fetch")
                .doesNotContainIgnoringCase("limit");
    }
}
