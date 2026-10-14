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
    void offsetOnlyAndPaginationHaveExactDialectContracts() {
        var mysql = com.wcdk.r2dbc.dialect.MySqlDatabaseDialect.INSTANCE;
        var dm = com.wcdk.r2dbc.dialect.DmDatabaseDialect.INSTANCE;
        assertThat(mysql.renderOffsetOnly(20)).isEqualTo("LIMIT 18446744073709551615 OFFSET 20");
        assertThat(PostgreSqlDatabaseDialect.INSTANCE.renderOffsetOnly(20)).isEqualTo("OFFSET 20");
        assertThat(OracleDatabaseDialect.INSTANCE.renderOffsetOnly(20)).isEqualTo("OFFSET 20 ROWS");
        assertThat(dm.renderOffsetOnly(20)).isEqualTo("OFFSET 20 ROWS");
        assertThat(DialectPagination.render(mysql,10,20L)).isEqualTo(" LIMIT 20, 10");
        assertThat(DialectPagination.render(mysql,10,null)).isEqualTo(" LIMIT 10");
        assertThat(DialectPagination.render(PostgreSqlDatabaseDialect.INSTANCE,10,20L)).isEqualTo(" LIMIT 10 OFFSET 20");
        assertThat(DialectPagination.render(OracleDatabaseDialect.INSTANCE,10,20L)).isEqualTo(" OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY");
        assertThat(DialectPagination.render(dm,10,20L)).isEqualTo(" OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY");
        for (var dialect : java.util.List.<com.wcdk.r2dbc.dialect.DatabaseDialect>of(mysql, PostgreSqlDatabaseDialect.INSTANCE, OracleDatabaseDialect.INSTANCE, dm)) {
            assertThatThrownBy(()->dialect.renderOffsetOnly(-1)).isInstanceOf(IllegalArgumentException.class);
            var builder = new RepositoryQuerySqlBuilder(new com.wcdk.r2dbc.config.WcdkR2dbcProperties(),null,dialect,org.mockito.Mockito.mock(SqlExecutionEngine.class));
            assertThat(builder.limitSql(new com.wcdk.r2dbc.query.QueryWrapper<>().offset(20),reactor.util.context.Context.empty()))
                    .isEqualTo(" " + dialect.renderOffsetOnly(20));
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
