package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.execution.RepositoryOperations;
import com.wcdk.r2dbc.execution.SqlLifecycleExecutor;
import com.wcdk.r2dbc.query.xml.*;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class XmlRepositoryContractTests {
    record Entity(String name) {}
    record Address(String city) {}
    record Nested(String name, Address address) {}
    static class Mutable { String name; }
    static class Immutable {
        final String name;
        Immutable(String name) { this.name = name; }
    }
    interface Repository {
        Mono<Entity> first(Entity entity, long id);
        Mono<Entity> later(long id, Entity entity);
        Mono<Long> count(long id);
        Mono<Integer> integer(long id);
        Mono<Boolean> bool(long id);
        Mono<Void> nothing(long id);
        Mono<Entity> missing(long id);
        Mono<List<Entity>> nestedGeneric(Entity entity);
        Mono<?> wildcard(Entity entity);
        Mono raw(Entity entity);
        Mono<Entity> ambiguous(Entity a, Entity b);
        Mono<Entity> find();
    }

    @Test void validatesWriteSignaturesAtCompilation() throws Exception {
        var registry = mock(RepositoryXmlRegistry.class);
        when(registry.find(eq(Repository.class), anyString())).thenAnswer(call -> Optional.of(
                new RepositoryStatement(Repository.class.getName(), call.getArgument(1), SqlCommandType.UPDATE, "UPDATE t SET n=1")));
        var compiler = new XmlStatementCompiler(Repository.class, registry);
        assertThat(XmlUpdateResultPlan.compile(method("first"), Repository.class).entityArgumentIndex()).isZero();
        assertThat(XmlUpdateResultPlan.compile(method("later"), Repository.class).entityArgumentIndex()).isEqualTo(1);
        for (String name : List.of("first", "later", "count", "integer", "bool", "nothing"))
            assertThat(compiler.compile(method(name))).isPresent();
        for (String name : List.of("missing", "nestedGeneric", "wildcard", "raw", "ambiguous"))
            assertThatThrownBy(() -> compiler.compile(method(name))).hasMessageContaining(name).hasMessageContaining("XML");
    }

    @Test void returnsCorrectEntityArgumentAndScalarResults() {
        var operations = operations();
        when(operations.updateWithoutLifecycle(anyString(), anyMap())).thenReturn(Mono.just(2L));
        var executor = executor(operations, mock(RepositoryXmlRegistry.class));
        var entity = new Entity("alice");
        assertThat(write(executor, "first", entity, 7L)).isSameAs(entity);
        assertThat(write(executor, "later", 7L, entity)).isSameAs(entity);
        assertThat(write(executor, "count", 7L)).isEqualTo(2L);
        assertThat(write(executor, "integer", 7L)).isEqualTo(2);
        assertThat(write(executor, "bool", 7L)).isEqualTo(true);
        assertThat(write(executor, "nothing", 7L)).isNull();
    }

    @Test void mapsRecordsConstructorsMutableObjectsAndNestedResultMaps() {
        var registry = mock(RepositoryXmlRegistry.class);
        register(registry, new ResultMapDefinition.Builder("entity", Entity.class.getName()).addIdMapping("NAME", "name").build());
        register(registry, new ResultMapDefinition.Builder("immutable", Immutable.class.getName()).addIdMapping("NAME", "name").build());
        register(registry, new ResultMapDefinition.Builder("mutable", Mutable.class.getName()).addIdMapping("NAME", "name").build());
        register(registry, new ResultMapDefinition.Builder("address", Address.class.getName()).addIdMapping("city", "city").build());
        register(registry, new ResultMapDefinition.Builder("nested", Nested.class.getName()).addIdMapping("name", "name")
                .addAssociationMapping("address", "address").build());
        var executor = executor(operationsForRow(), registry);
        assertThat(read(executor, "entity")).isEqualTo(new Entity("alice"));
        assertThat(((Immutable) read(executor, "immutable")).name).isEqualTo("alice");
        assertThat(((Mutable) read(executor, "mutable")).name).isEqualTo("alice");
        assertThat(read(executor, "nested")).isEqualTo(new Nested("alice", new Address("Shanghai")));
    }

    @Test void diagnosesUnknownPropertiesAndUnmatchedDiscriminator() {
        var registry = mock(RepositoryXmlRegistry.class);
        register(registry, new ResultMapDefinition.Builder("bad", Entity.class.getName()).addIdMapping("name", "typo").build());
        register(registry, new ResultMapDefinition.Builder("branch", Entity.class.getName()).discriminatorColumn("name")
                .addDiscriminatorMapping("alice", "good").build());
        register(registry, new ResultMapDefinition.Builder("good", Entity.class.getName()).addIdMapping("name", "name").build());
        register(registry, new ResultMapDefinition.Builder("unmatched", Entity.class.getName()).discriminatorColumn("name")
                .addDiscriminatorMapping("bob", "good").build());
        var executor = executor(operationsForRow(), registry);
        assertThat(read(executor, "branch")).isEqualTo(new Entity("alice"));
        assertThatThrownBy(() -> read(executor, "bad")).hasMessageContaining("bad").hasMessageContaining("typo").hasMessageContaining(Entity.class.getName());
        assertThatThrownBy(() -> read(executor, "unmatched")).hasMessageContaining("unmatched").hasMessageContaining("name").hasMessageContaining(Entity.class.getName());
    }

    private RepositoryOperations operations() {
        var operations = mock(RepositoryOperations.class, CALLS_REAL_METHODS);
        when(operations.lifecycleExecutor()).thenReturn(new SqlLifecycleExecutor());
        return operations;
    }
    private RepositoryOperations operationsForRow() {
        var operations = operations();
        var row = mock(Row.class); var metadata = mock(RowMetadata.class);
        var name = mock(ColumnMetadata.class); var city = mock(ColumnMetadata.class);
        when(name.getName()).thenReturn("name"); when(city.getName()).thenReturn("city");
        when(row.getMetadata()).thenReturn(metadata);
        doReturn(List.of(name, city)).when(metadata).getColumnMetadatas();
        when(row.get("name")).thenReturn("alice"); when(row.get("city")).thenReturn("Shanghai");
        when(operations.queryWithoutLifecycle(anyString(), anyMap(), any())).thenAnswer(call -> {
            BiFunction<Row, RowMetadata, Object> mapper = call.getArgument(2);
            return Flux.defer(() -> Flux.just(mapper.apply(row, metadata)));
        });
        return operations;
    }
    private XmlRepositoryExecutor executor(RepositoryOperations operations, RepositoryXmlRegistry registry) {
        return new XmlRepositoryExecutor(null, Repository.class, registry, new SqlExecutionEngine(operations), new RepositoryParameterBinder());
    }
    private static Method method(String name) {
        return Arrays.stream(Repository.class.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }
    private Object write(XmlRepositoryExecutor executor, String name, Object... args) {
        return ((Mono<?>) executor.executeXmlStatement(new RepositoryStatement(Repository.class.getName(), name,
                SqlCommandType.UPDATE, "UPDATE t SET n=1"), method(name), args)).block();
    }
    private Object read(XmlRepositoryExecutor executor, String resultMap) {
        var statement = new RepositoryStatement(Repository.class.getName(), "find", SqlCommandType.SELECT,
                DynamicSqlSource.staticSql("SELECT name, city FROM t"), null, resultMap);
        return ((Mono<?>) executor.executeXmlStatement(statement, method("find"), new Object[0])).block();
    }
    private void register(RepositoryXmlRegistry registry, ResultMapDefinition map) {
        when(registry.findResultMap(map.id())).thenReturn(Optional.of(map));
    }
}
