package com.wcdk.r2dbc.execution;

import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.sql.Timestamp;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParameterBinderTests {

    private final ParameterBinder binder = new ParameterBinder();

    @Test
    void bindsTypedNullAndRejectsUntypedNull() {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(client.sql("SELECT * FROM users WHERE name = :name")).thenReturn(spec);
        when(spec.bindNull("name", String.class)).thenReturn(spec);

        binder.bind(client, "SELECT * FROM users WHERE name = :name",
                Map.of("name", SqlParameter.nullOf(String.class)));

        verify(spec).bindNull("name", String.class);
        assertThatThrownBy(() -> binder.bind(spec, "name", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SqlParameter.nullOf");
    }

    @Test
    void diagnosesMissingUnusedAndInvalidParametersWithSql() {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        String sql = "SELECT * FROM users WHERE id = :id";
        when(client.sql(sql)).thenReturn(spec);
        when(spec.bind("extra", 1L)).thenReturn(spec);
        when(spec.bind("id", 2L)).thenReturn(spec);

        assertThatThrownBy(() -> binder.bind(client, sql, Map.of()))
                .hasMessageContaining("缺少SQL参数")
                .hasMessageContaining(sql);
        assertThatThrownBy(() -> binder.bind(client, sql, Map.of("extra", 1L)))
                .hasMessageContaining("缺少SQL参数")
                .hasMessageContaining("id");
        assertThatThrownBy(() -> binder.bind(client, sql, Map.of("id", 2L, "extra", 1L)))
                .hasMessageContaining("未使用的SQL参数")
                .hasMessageContaining("extra");
        assertThatThrownBy(() -> binder.bind(client, sql, Map.of("bad-name", 1L)))
                .hasMessageContaining("无效的SQL参数名")
                .hasMessageContaining(sql);
        assertThatThrownBy(() -> binder.bind(spec, -1, 1L))
                .hasMessageContaining("不能为负数");
    }


    @Test
    void rejectsExtrasEvenWhenSqlHasNoMarkers() {
        var client = mock(DatabaseClient.class);
        assertThatThrownBy(() -> binder.bind(client, "SELECT 1", Map.of("extra", 1)))
                .hasMessageContaining("未使用").hasMessageContaining("extra");
        assertThatThrownBy(() -> binder.bind(client, "SELECT 1", Map.of(0, 1)))
                .hasMessageContaining("未使用");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void validatesIndexedMarkersAndIgnoresQuotedMarkers() {
        for (String sql : java.util.List.of("SELECT ? + ?", "SELECT $1 + $2", "SELECT :1 + :2")) {
            var client = mock(DatabaseClient.class);
            var spec = mock(DatabaseClient.GenericExecuteSpec.class, org.mockito.Answers.RETURNS_SELF);
            when(client.sql(sql)).thenReturn(spec);
            assertThat(binder.bind(client, sql, Map.of(0, 10, 1, 20))).isSameAs(spec);
            verify(spec).bind(0, 10); verify(spec).bind(1, 20);
            assertThatThrownBy(() -> binder.bind(client, sql, Map.of(0, 10)))
                    .hasMessageContaining("缺少").hasMessageContaining("1");
            assertThatThrownBy(() -> binder.bind(client, sql, Map.of(0, 10, 1, 20, 2, 30)))
                    .hasMessageContaining("未使用").hasMessageContaining("2");
        }
        assertThat(NamedParameterParser.placeholders("SELECT '$1 ?', $$ $2 ? $$, q'[ :1 ? ]' -- ?\n/* $3 */" ).indexes()).isEmpty();
    }

    @Test
    void defaultConverterKeepsR2dbcNativeJavaTypes() {
        Instant instant = Instant.parse("2026-08-11T04:05:06Z");

        assertThat(new DefaultParameterValueConverter().convert(instant)).isSameAs(instant);
        assertThat(new DefaultParameterValueConverter().convert(LocalDateTime.of(2026, 8, 11, 12, 30)))
                .isInstanceOf(LocalDateTime.class);
    }

    @Test
    void dmConverterOnlyAdaptsDmCompatibilityTypes() {
        Instant instant = Instant.parse("2026-08-11T04:05:06Z");

        assertThat(new DmParameterValueConverter().convert(instant)).isEqualTo(Date.from(instant));
        assertThat(new DmParameterValueConverter().convert(LocalDateTime.of(2026, 8, 11, 12, 30)))
                .isInstanceOf(Timestamp.class);
        assertThat(new DmParameterValueConverter().convert(LocalDate.of(2026, 8, 11)))
                .isInstanceOf(java.sql.Date.class);
        assertThat(new DmParameterValueConverter().nullType(LocalDate.class))
                .isEqualTo(java.sql.Date.class);
        assertThat(new DmParameterValueConverter().nullType(LocalDateTime.class))
                .isEqualTo(Timestamp.class);
    }    @Test
    void dmBinderUsesJdbcTypesForTemporalNulls() {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        String sql = "INSERT INTO assets (scrap_date) VALUES (:scrapDate)";
        when(client.sql(sql)).thenReturn(spec);
        when(spec.bindNull("scrapDate", java.sql.Date.class)).thenReturn(spec);

        new ParameterBinder(new DmParameterValueConverter()).bind(client, sql,
                Map.of("scrapDate", SqlParameter.nullOf(LocalDate.class)));

        verify(spec).bindNull("scrapDate", java.sql.Date.class);
    }

    @Test
    void scannerIgnoresCastsQuotedTextAndComments() {
        String sql = "SELECT value::text, ':quoted' -- :line\n/* :block */ WHERE id = :id";
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(client.sql(sql)).thenReturn(spec);
        when(spec.bind("id", 7L)).thenReturn(spec);

        binder.bind(client, sql, Map.of("id", 7L));

        verify(spec).bind("id", 7L);
    }
}
