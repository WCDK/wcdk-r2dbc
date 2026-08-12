package com.wcdk.r2dbc.execution;

import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
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
    void normalizesJavaTimeValuesForJdbcDrivers() {
        Instant instant = Instant.parse("2026-08-11T04:05:06Z");

        assertThat(ParameterBinder.normalizeParameterValue(instant)).isEqualTo(Date.from(instant));
        assertThat(ParameterBinder.normalizeParameterValue(LocalDateTime.of(2026, 8, 11, 12, 30)))
                .isInstanceOf(java.sql.Timestamp.class);
        assertThat(ParameterBinder.normalizeParameterValue(LocalDate.of(2026, 8, 11)))
                .isInstanceOf(java.sql.Date.class);
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
