package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.math.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MappingBindingBatchTests {
    record IntegerRow(Integer value) {}
    record ShortRow(Short value) {}
    record ByteRow(Byte value) {}
    record LongRow(Long value) {}
    record DecimalRow(BigDecimal value) {}
    record TimeRow(Date createdAt) {}
    record StringRow(String value) {}
    record ThrowingRow(String value) {
        ThrowingRow { throw FAILURE; }
    }
    static final IllegalArgumentException FAILURE = new IllegalArgumentException("constructor business failure");
    enum Status { ACTIVE }

    @Test void exactNumericConversionsDiagnoseColumnValueAndTarget() {
        var mapper = new R2dbcRowMapper();
        for (var example : List.of(new Object[]{2147483648L, IntegerRow.class, "Integer"},
                new Object[]{32768L, ShortRow.class, "Short"}, new Object[]{128L, ByteRow.class, "Byte"},
                new Object[]{new BigInteger("9223372036854775808"), LongRow.class, "Long"})) {
            assertThatThrownBy(() -> mapper.map(row("value", example[0]), (Class<?>) example[1]))
                    .hasMessageContaining("value").hasMessageContaining(example[0].toString())
                    .hasMessageContaining((String) example[2]);
        }
        assertThat(mapper.map(row("value", 2147483647L), IntegerRow.class).value()).isEqualTo(Integer.MAX_VALUE);
        assertThat(mapper.map(row("value", 9007199254740993L), DecimalRow.class).value())
                .isEqualTo(new BigDecimal("9007199254740993"));
        assertThat(mapper.convertValue(new BigInteger("90071992547409931234"), BigDecimal.class))
                .isEqualTo(new BigDecimal("90071992547409931234"));
    }

    @Test void constructorFailureExposesOriginalCauseForRowAndResultMap() {
        var mapper = new R2dbcRowMapper();
        assertThatThrownBy(() -> mapper.map(row("value", "bad"), ThrowingRow.class))
                .hasMessageContaining("constructor business failure").hasCause(FAILURE);
        assertThatThrownBy(() -> mapper.mapProperties(Map.of("value", "bad"), ThrowingRow.class, "test.result"))
                .hasMessageContaining("test.result").hasMessageContaining("constructor business failure").hasCause(FAILURE);
    }

    @Test void binaryToStringIsRejectedWithColumnContext() {
        assertThatThrownBy(() -> new R2dbcRowMapper().map(row("value", new byte[]{1}), StringRow.class))
                .hasMessageContaining("value").hasMessageContaining("byte[]").hasMessageContaining("String");
    }

    @Test void mapAndEntityQueriesNormalizeTheSameTemporalColumn() {
        Instant instant = Instant.parse("2026-10-13T01:00:00Z");
        var fixture = new Fixture(row("created_at", instant));
        var values = fixture.query.queryOne("SELECT created_at").block();
        var entity = fixture.query.queryOne("SELECT created_at", (row, metadata) ->
                new R2dbcRowMapper().map(row, TimeRow.class)).block();
        assertThat(values.get("created_at")).isEqualTo(Date.from(instant));
        assertThat(entity.createdAt()).isEqualTo(values.get("created_at"));
    }

    @Test void positionalKeysBindIdenticallyWithAndWithoutLifecycleForQueriesAndUpdates() {
        var fixture = new Fixture(row("value", 1));
        Map<Integer, Object> parameters = Map.of(0, 42);
        StepVerifier.create(fixture.query.query("SELECT ?", parameters, (r, m) -> r.get("value")))
                .expectNext(1).verifyComplete();
        StepVerifier.create(fixture.query.queryWithoutLifecycle("SELECT ?", parameters, (r, m) -> r.get("value")))
                .expectNext(1).verifyComplete();
        StepVerifier.create(fixture.update.update("UPDATE t SET value = ?", parameters)).expectNext(1L).verifyComplete();
        StepVerifier.create(fixture.update.updateWithoutLifecycle("UPDATE t SET value = ?", parameters))
                .expectNext(1L).verifyComplete();
        verify(fixture.spec, times(4)).bind(0, 42);
    }

    @Test void parserRecognizesEscapesCommentsSlicesAndJsonOperators() {
        assertThat(NamedParameterParser.parse("SELECT 'a\\'b :hidden', :visible" )).containsExactly("visible");
        assertThat(NamedParameterParser.parse("SELECT :visible # :hidden ?\n, :next"))
                .containsExactly("visible", "next");
        var slice = NamedParameterParser.placeholders("SELECT items[1:3], :id");
        assertThat(slice.names()).containsExactly("id");
        assertThat(slice.indexes()).isEmpty();
        var json = NamedParameterParser.placeholders("SELECT data ? 'key', data ?| array['a'], data ?& array['b'] WHERE id = ?");
        assertThat(json.indexes()).containsExactly(0);
        var operand = NamedParameterParser.placeholders("SELECT data ? :key WHERE id = $1");
        assertThat(operand.names()).containsExactly("key");
        assertThat(operand.indexes()).containsExactly(0);
        assertThat(NamedParameterParser.placeholders("SELECT ?, ? FROM t WHERE id = ?").indexes())
                .containsExactly(0, 1, 2);
        assertThat(NamedParameterParser.placeholders("SELECT id FROM t WHERE name LIKE ? ESCAPE '!' AND id BETWEEN ? AND ?").indexes())
                .containsExactly(0, 1, 2);

    }

    @Test void requestSnapshotKeepsOrderIndexesAndNullDiagnostic() {
        Map<Object, Object> parameters = new LinkedHashMap<>();
        parameters.put("first", 1); parameters.put(0, 2); parameters.put("nullable", null);
        var request = SqlExecutionRequest.update("UPDATE t SET a = :first, b = ?, c = :nullable", parameters);
        parameters.clear();
        assertThat(new ArrayList<Object>(request.namedParameters().keySet())).containsExactly("first", 0, "nullable");
        assertThatThrownBy(() -> request.namedParameters().clear()).isInstanceOf(UnsupportedOperationException.class);
        var fixture = new Fixture(row("value", 1));
        assertThatThrownBy(() -> new ParameterBinder().bind(fixture.client, request.sql(), request.namedParameters()))
                .hasMessageContaining("nullable").hasMessageContaining("SqlParameter.nullOf");
        var typed = SqlExecutionRequest.update("UPDATE t SET c = :nullable", Map.of("nullable", SqlParameter.nullOf(String.class)));
        new ParameterBinder().bind(fixture.client, typed.sql(), typed.namedParameters());
        verify(fixture.spec).bindNull("nullable", String.class);
    }

    @Test void enumValuesBecomeNamesForNamedIndexedTypedAndDmBindings() {
        var fixture = new Fixture(row("value", 1));
        var binder = new ParameterBinder();
        binder.bind(fixture.spec, "status", Status.ACTIVE);
        binder.bind(fixture.spec, 0, Status.ACTIVE);
        binder.bind(fixture.spec, "typed", SqlParameter.of(Status.ACTIVE));
        verify(fixture.spec).bind("status", "ACTIVE");
        verify(fixture.spec).bind(0, "ACTIVE");
        verify(fixture.spec).bind("typed", "ACTIVE");
        binder.bind(fixture.spec, "nullEnum", SqlParameter.nullOf(Status.class));
        verify(fixture.spec).bindNull("nullEnum", String.class);
        assertThat(new DmParameterValueConverter().convert(Status.ACTIVE)).isEqualTo("ACTIVE");
    }

    static Row row(String name, Object value) {
        var row = mock(Row.class); var metadata = mock(RowMetadata.class); var column = mock(ColumnMetadata.class);
        when(column.getName()).thenReturn(name);
        doReturn(List.of(column)).when(metadata).getColumnMetadatas();
        when(row.getMetadata()).thenReturn(metadata); when(row.get(name)).thenReturn(value);
        return row;
    }

    static class Fixture {
        final DatabaseClient client = mock(DatabaseClient.class);
        final DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        final R2dbcQueryOperations query;
        final R2dbcUpdateOperations update;
        @SuppressWarnings({"unchecked", "rawtypes"})
        Fixture(Row row) {
            when(client.sql(anyString())).thenReturn(spec);
            when(spec.bind(anyInt(), any())).thenReturn(spec); when(spec.bind(anyString(), any())).thenReturn(spec);
            when(spec.bindNull(anyString(), any())).thenReturn(spec);
            when(spec.filter(any(org.springframework.r2dbc.core.StatementFilterFunction.class))).thenReturn(spec);
            var fetched = mock(FetchSpec.class);
            when(fetched.rowsUpdated()).thenReturn(Mono.just(1L)); when(spec.fetch()).thenReturn(fetched);
            doAnswer(invocation -> {
                BiFunction<Row, RowMetadata, Object> mapper = invocation.getArgument(0);
                var rows = mock(RowsFetchSpec.class);
                when(rows.all()).thenAnswer(ignored -> Mono.fromSupplier(() -> mapper.apply(row, row.getMetadata())).flux());
                return rows;
            }).when(spec).map(any(BiFunction.class));
            var logger = new R2dbcSqlLogger(new WcdkR2dbcProperties(), new WcdkSpringR2dbcProperties());
            query = new R2dbcQueryOperations(client, new ParameterBinder(), new SqlLifecycleExecutor(), logger);
            update = new R2dbcUpdateOperations(client, new ParameterBinder(), new SqlLifecycleExecutor(), logger);
        }
    }
}
