package com.wcdk.r2dbc.repository;

import org.junit.jupiter.api.Test;
import com.wcdk.r2dbc.dialect.OracleDatabaseDialect;
import com.wcdk.r2dbc.dialect.PostgreSqlDatabaseDialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectPaginationTests {

    @Test
    void rejectsOverflowBeforeIntegerConversionForAllDialects() {
        for (var dialect : java.util.List.<com.wcdk.r2dbc.dialect.DatabaseDialect>of(
                PostgreSqlDatabaseDialect.INSTANCE, OracleDatabaseDialect.INSTANCE,
                com.wcdk.r2dbc.dialect.MySqlDatabaseDialect.INSTANCE, com.wcdk.r2dbc.dialect.DmDatabaseDialect.INSTANCE)) {
            assertThat(DialectPagination.render(dialect, Integer.MAX_VALUE, 4_000_000_000L))
                    .contains("2147483647", "4000000000");
            assertThat(DialectPagination.render(dialect, 1, null)).contains("1");
            for (long limit : new long[]{0, -1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
                assertThatThrownBy(() -> DialectPagination.render(dialect, limit, null))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> dialect.pagination("SELECT 1", 0, limit))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> DialectPagination.render(dialect, 1, -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

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
