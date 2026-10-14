package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.execution.*;
import com.wcdk.r2dbc.execution.lifecycle.*;
import com.wcdk.r2dbc.query.xml.*;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.PageRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import java.util.*;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryExecutionBatchTests {
    static class Entity {
        @Id Long key; String id; String updatedBy; String coordinator; String coOrdinator; Integer delFlg;
    }
    interface Good extends BaseRepository<Entity> {
        Mono<Integer> countById(String id);
        Mono<Integer> countBy();
        Flux<Entity> findBy();
    }
    interface Methods {
        Mono<Long> updateUpdatedByByKey(String updatedBy, Long key);
        Mono<Long> updateCOORDINATORByKey(String value, Long key);
        Mono<Entity> findByCOORDINATOR(String value);
        Mono<Entity> findByCoOrdinator(String value);
        Mono<Entity> findAllById(String value);
        Mono<Entity> findOneById(String value);
        Mono<Long> updateIdById(String value, Long key);
        Mono<Long> updateByIdById(String value, Long key);
        Mono<Entity> findByIdNotLike(String value);
        Mono<Entity> findByMissing(String value);
    }
    interface Xml {
        Mono<Boolean> bool(); Flux<Boolean> bools();
        List<Entity> invalid(); Mono<Long> write(@Param("id") long id);
        Mono<Long> none(@Param("unused") long unused);
        Mono<Long> partial(@Param("id") long id, @Param("unused") long unused);
    }

    record Annotated(Long key, String label) {
        Annotated(Long key, @com.wcdk.r2dbc.annotation.Column("explicit_label") String label) {
            this.key = key; this.label = label;
        }
    }
    @Test void constructorColumnIsSharedBySqlMetadataAndRowMapping() {
        var metadata = new RepositoryMetadata(Annotated.class, new WcdkR2dbcProperties());
        assertThat(metadata.columnByName("label").name()).isEqualTo("\"explicit_label\"");
        assertThat(metadata.columnByName("label").rawName()).isEqualTo("explicit_label");
    }

    @Test void integerCountUsesScalarRouteAndExactRange() {
        var fixture = new Fixture();
        StepVerifier.create(fixture.repository.countById("x")).expectNext(1).verifyComplete();
        when(fixture.row.get(0)).thenReturn(2147483648L);
        StepVerifier.create(fixture.repository.countById("x")).expectError(ArithmeticException.class).verify();
    }

    @Test void unconditionalDerivedQueriesBindLogicalDeleteParameters() {
        var fixture = new Fixture();
        StepVerifier.create(fixture.repository.findBy()).expectNextCount(1).verifyComplete();
        StepVerifier.create(fixture.repository.countBy()).expectNext(1).verifyComplete();
        var parameters = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(fixture.ops, times(2)).queryWithoutLifecycle(anyString(), parameters.capture(), any());
        for (Object values : parameters.getAllValues()) assertThat(((Map<?, ?>) values).get("logicNotDeleteValue")).isEqualTo(0);

    }

    @Test void updateDelimiterRespectsFieldContainingBy() throws Exception {
        var resolver = resolver();
        var method = Methods.class.getMethod("updateUpdatedByByKey", String.class, Long.class);
        var parsed = resolver.resolve(method, new Object[]{"editor", 7L});
        assertThat(parsed.sql()).contains("\"updated_by\" = :set0", "\"key\" = :p0");
        assertThat(parsed.parameters()).containsEntry("set0", "editor").containsEntry("p0", 7L);

        var idMethod = Methods.class.getMethod("updateIdById", String.class, Long.class);
        var idParsed = resolver.resolve(idMethod, new Object[]{"external-id", 8L});
        assertThat(idParsed.sql()).contains("\"id\" = :set0", "\"key\" = :p0");
        assertThat(idParsed.parameters()).containsEntry("set0", "external-id").containsEntry("p0", 8L);
    }

    @Test void exactCaseWinsAndNormalizedAmbiguityListsBothFields() throws Exception {
        assertThat(resolver().compile(Methods.class.getMethod("findByCoOrdinator", String.class))
                .conditions().getFirst().fieldName()).isEqualTo("coOrdinator");
        assertThatThrownBy(() -> resolver().compile(Methods.class.getMethod("findByCOORDINATOR", String.class)))
                .hasMessageContaining("歧义").hasMessageContaining("coordinator").hasMessageContaining("coOrdinator");
        assertThatThrownBy(() -> resolver().compile(Methods.class.getMethod("updateCOORDINATORByKey", String.class, Long.class)))
                .hasMessageContaining("歧义").hasMessageContaining("coordinator").hasMessageContaining("coOrdinator");
    }

    @Test void unsupportedNamesFailAtCompilationWithActionableGuidance() {
        var compiler = new DerivedMethodCompiler(Methods.class, metadata(), new WcdkR2dbcProperties());
        for (String name : List.of("findAllById", "findOneById")) {
            var method = Arrays.stream(Methods.class.getMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            assertThatThrownBy(() -> compiler.compile(method)).hasMessageContaining(name)
                    .hasMessageContaining("findBy");
        }
        var legacyUpdate = Arrays.stream(Methods.class.getMethods())
                .filter(method -> method.getName().equals("updateByIdById")).findFirst().orElseThrow();
        assertThatThrownBy(() -> compiler.compile(legacyUpdate))
                .hasMessageContaining("updateByIdById")
                .hasMessageContaining("update<更新字段>By<条件>");
    }

    @Test void invalidFieldsAndOperatorsHaveDifferentDiagnostics() throws Exception {
        assertThatThrownBy(() -> resolver().compile(Methods.class.getMethod("findByIdNotLike", String.class)))
                .hasMessageContaining("算子不支持").hasMessageContaining("NotLike").hasMessageContaining("支持算子");
        assertThatThrownBy(() -> resolver().compile(Methods.class.getMethod("findByMissing", String.class)))
                .hasMessageContaining("字段不存在").hasMessageContaining("候选字段").hasMessageContaining("updatedBy");
    }

    @Test void nonPrimaryIdUpdateUsesSeparateSetAndKeyBindings() {
        var fixture = new Fixture(); var entity = new Entity(); entity.key = 7L; entity.id = "external-id";
        StepVerifier.create(fixture.repository.updateById(entity)).expectNext(1L).verifyComplete();
        var parameters = org.mockito.ArgumentCaptor.forClass(Map.class);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.ops).updateWithoutLifecycle(sql.capture(), parameters.capture());
        assertThat(sql.getValue()).contains("\"id\" = :set_id", "\"key\" = :__pk");
        assertThat(parameters.getValue()).containsEntry("set_id", "external-id").containsEntry("__pk", 7L);
    }

    @Test void nullKeysAreRejectedConsistentlyBeforeSql() {
        var fixture = new Fixture();
        StepVerifier.create(fixture.repository.deleteById(null)).expectErrorMatches(e -> e.getMessage().contains("deleteById") && e.getMessage().contains("主键不能为空")).verify();
        StepVerifier.create(fixture.repository.selectById(null)).expectErrorMatches(e -> e.getMessage().contains("selectById") && e.getMessage().contains("主键不能为空")).verify();
        StepVerifier.create(fixture.repository.updateById(new Entity())).expectErrorMatches(e -> e.getMessage().contains("updateById") && e.getMessage().contains("主键不能为空")).verify();
        verify(fixture.ops, never()).updateWithoutLifecycle(anyString(), anyMap());
        verify(fixture.ops, never()).queryWithoutLifecycle(anyString(), anyMap(), any());
    }

    @Test void pageRecordsAndCountBothRunLifecycleWithSeparateExecutionIds() {
        var fixture = new Fixture();
        StepVerifier.create(fixture.repository.selectPage(PageRequest.of(0, 2)))
                .assertNext(page -> assertThat(page.getContent()).hasSize(1)).verifyComplete();
        assertThat(fixture.before).hasSize(2); assertThat(fixture.after).hasSize(2);
        assertThat(fixture.before.stream().map(SqlExecutionContext::getExecutionId).distinct()).hasSize(2);
        verify(fixture.ops, never()).query(anyString(), anyMap(), any());
    }

    @Test void xmlNativeBooleansUseSameConversionForMonoAndFlux() throws Exception {
        var fixture = new Fixture(); when(fixture.row.get(0)).thenReturn(true);
        for (String name : List.of("bool", "bools")) {
            Object result = xml(fixture, name, SqlCommandType.SELECT, "SELECT flag FROM t", true, new Object[0]);
            StepVerifier.create(Flux.from((org.reactivestreams.Publisher<?>) result)).assertNext(value -> assertThat(value).isEqualTo(true)).verifyComplete();
        }
    }

    @Test void xmlSelectRejectsListDuringCompilation() throws Exception {
        var method = Xml.class.getMethod("invalid");
        assertThatThrownBy(() -> xmlPlan(method, SqlCommandType.SELECT, "SELECT flag"))
                .hasMessageContaining(Xml.class.getName() + ".invalid").hasMessageContaining("Mono/Flux");
    }

    @Test void xmlUnusedParametersAreStrictAndCompatibilitySwitchIsExplicit() throws Exception {
        var fixture = new Fixture();
        StepVerifier.create((Mono<?>) xml(fixture, "none", SqlCommandType.SELECT, "SELECT 1", true, new Object[]{1L}))
                .expectErrorMatches(e -> e.getMessage().contains("unused") && e.getMessage().contains(Xml.class.getName()+".none")).verify();
        StepVerifier.create((Mono<?>) xml(fixture, "partial", SqlCommandType.SELECT, "SELECT #{id}", true, new Object[]{1L,2L}))
                .expectErrorMatches(e -> e.getMessage().contains("unused")).verify();
        StepVerifier.create((Mono<?>) xml(fixture, "none", SqlCommandType.SELECT, "SELECT 1", false, new Object[]{1L}))
                .expectNextCount(1).verifyComplete();
    }

    @Test void xmlRuntimeRenderFailureIncludesStatementId() throws Exception {
        var fixture = new Fixture();
        StepVerifier.create((Mono<?>) xml(fixture, "bool", SqlCommandType.SELECT, "SELECT #{missing + 1}", true, new Object[0]))
                .expectErrorMatches(e -> e.getMessage().contains(Xml.class.getName()+".bool") && e.getCause() != null).verify();
    }

    @Test void cachedXmlWritePlanIsConsumedWithoutRuntimeCompilation() throws Exception {
        var fixture = new Fixture(); var method = Xml.class.getMethod("write", long.class);
        var plan = xmlPlan(method, SqlCommandType.UPDATE, "UPDATE t SET n=#{id}");
        assertThat(plan.sqlPlan().compiledPlan()).isInstanceOf(XmlUpdateResultPlan.class);
        var executor = new XmlRepositoryExecutor(null, Xml.class, mock(RepositoryXmlRegistry.class), new SqlExecutionEngine(fixture.ops), new RepositoryParameterBinder());
        try (var compilation = mockStatic(XmlUpdateResultPlan.class)) {
            StepVerifier.create((Mono<?>) executor.execute(plan, new Object[]{1L}, Context.empty(), null)).assertNext(value -> assertThat(value).isEqualTo(1L)).verifyComplete();
            compilation.verifyNoInteractions();
        }
    }

    private static RepositoryMetadata metadata() { return new RepositoryMetadata(Entity.class, new WcdkR2dbcProperties()); }
    private static CustomMethodResolver resolver() { return new CustomMethodResolver(metadata(), 1, 0); }
    private RepositoryMethodPlan xmlPlan(java.lang.reflect.Method method, SqlCommandType type, String sql) {
        var registry = mock(RepositoryXmlRegistry.class);
        when(registry.find(Xml.class, method.getName())).thenReturn(Optional.of(new RepositoryStatement(Xml.class.getName(), method.getName(), type, sql)));
        return new XmlStatementCompiler(Xml.class, registry).compile(method).orElseThrow();
    }
    private Object xml(Fixture fixture, String name, SqlCommandType type, String sql, boolean strict, Object[] args) {
        var method = Arrays.stream(Xml.class.getMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        var executor = new XmlRepositoryExecutor(null, Xml.class, mock(RepositoryXmlRegistry.class), new SqlExecutionEngine(fixture.ops), new RepositoryParameterBinder(), strict);
        return executor.execute(xmlPlan(method, type, sql), args, Context.empty(), null);
    }
    static class Fixture {
        final RepositoryOperations ops = mock(RepositoryOperations.class, CALLS_REAL_METHODS);
        final Row row = mock(Row.class); final RowMetadata rows = mock(RowMetadata.class);
        final List<SqlExecutionContext> before = new ArrayList<>(), after = new ArrayList<>();
        final Good repository;
        @SuppressWarnings({"rawtypes", "unchecked"}) Fixture() {
            var properties = new WcdkR2dbcProperties(); var factory = mock(ConnectionFactory.class);
            when(factory.getMetadata()).thenReturn(() -> "PostgreSQL");
            var client = mock(DatabaseClient.class); when(client.getConnectionFactory()).thenReturn(factory); when(ops.databaseClient()).thenReturn(client);
            when(ops.lifecycleExecutor()).thenReturn(new SqlLifecycleExecutor(new SqlLifecycleInterceptorChain(List.of(new SqlLifecycleInterceptor() {
                public void beforeExecute(SqlExecutionContext context) { before.add(context); }
                public void afterExecute(SqlExecutionContext context) { after.add(context); }
            }), List.of())));
            when(row.get(0)).thenReturn(1L); when(row.getMetadata()).thenReturn(rows);
            var column = mock(ColumnMetadata.class); when(column.getName()).thenReturn("id");
            doReturn(List.of(column)).when(rows).getColumnMetadatas(); when(row.get("id")).thenReturn("stored");
            when(ops.queryOneWithoutLifecycle(anyString(), anyMap(), any())).thenAnswer(call -> {
                BiFunction mapper = call.getArgument(2); return Mono.fromSupplier(() -> mapper.apply(row, rows));
            });
            when(ops.queryWithoutLifecycle(anyString(), anyMap(), any())).thenAnswer(call -> {
                BiFunction mapper = call.getArgument(2); return Flux.defer(() -> Flux.just(mapper.apply(row, rows)));
            });
            when(ops.map(any(), any())).thenAnswer(call -> new R2dbcRowMapper().map(call.getArgument(0), call.getArgument(1)));
            when(ops.updateWithoutLifecycle(anyString(), anyMap())).thenReturn(Mono.just(1L));
            repository = (Good) new RepositoryProxyFactory(ops, properties, mock(RepositoryXmlRegistry.class)).create(Good.class);
        }
    }
}
