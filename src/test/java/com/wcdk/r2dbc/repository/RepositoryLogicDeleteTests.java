package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.dialect.*;
import com.wcdk.r2dbc.query.QueryWrapper;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.relational.core.mapping.Column;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryLogicDeleteTests {

    static Stream<Object[]> configurations() {
        return Stream.<DatabaseDialect>of(MySqlDatabaseDialect.INSTANCE, PostgreSqlDatabaseDialect.INSTANCE,
                        OracleDatabaseDialect.INSTANCE, DmDatabaseDialect.INSTANCE)
                .flatMap(dialect -> Stream.of(new Object[]{dialect, true}, new Object[]{dialect, false}));
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void similarColumnsAndValuesKeepDefaultFilter(DatabaseDialect dialect, boolean quote) {
        var fixture = fixture(dialect, quote);
        String deleted = fixture.metadata.logicDeleteColumn().name();
        for (QueryWrapper<?> wrapper : List.of(new QueryWrapper<>(),
                new QueryWrapper<>().eq("deletedFlagExtra", 1),
                new QueryWrapper<>().eq("name", deleted),
                new QueryWrapper<>().eq("name", "x").or(q -> q.eq("deletedFlagExtra", 1)),
                new QueryWrapper<>().orderByAsc("delFlg"))) {
            var where = fixture.builder.buildWhere(wrapper);
            assertThat(where.sql()).endsWith(deleted + " = :logicNotDeleteValue");
            assertThat(where.parameters()).containsEntry("logicNotDeleteValue", 0);
        }
        assertThat(fixture.builder.buildWhere(null).parameters())
                .containsExactly(Map.entry("logicNotDeleteValue", 0));
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void explicitColumnAliasesAndCaseVariantsOverrideDefault(DatabaseDialect dialect, boolean quote) {
        var fixture = fixture(dialect, quote);
        String deleted = fixture.metadata.logicDeleteColumn().name();
        for (String name : List.of("delFlg", "DELFLG", "deleted_flag", "DELETED_FLAG", deleted)) {
            var where = fixture.builder.buildWhere(new QueryWrapper<>().eq(name, 1));
            assertThat(where.sql()).isEqualTo(" WHERE " + deleted + " = :p0");
            assertThat(where.parameters()).containsExactly(Map.entry("p0", 1));
        }
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void checksAllAstNodesIncludingNestedAndEmptyIn(DatabaseDialect dialect, boolean quote) {
        var fixture = fixture(dialect, quote);
        for (QueryWrapper<?> wrapper : List.of(
                new QueryWrapper<>().ne("delFlg", 0),
                new QueryWrapper<>().eq("delFlg", null),
                new QueryWrapper<>().isNull("delFlg"),
                new QueryWrapper<>().isNotNull("delFlg"),
                new QueryWrapper<>().in("delFlg", List.of(0, 1)),
                new QueryWrapper<>().notIn("delFlg", List.of(0)),
                new QueryWrapper<>().in("delFlg", List.of()),
                new QueryWrapper<>().notIn("delFlg", List.of()),
                new QueryWrapper<>().eq("name", "x")
                        .or(q -> q.eq("name", "y").and(n -> n.eq("DELFLG", 1))))) {
            var where = fixture.builder.buildWhere(wrapper);
            assertThat(where.sql()).doesNotContain(":logicNotDeleteValue");
            assertThat(where.parameters()).doesNotContainKey("logicNotDeleteValue");
        }
        var where = fixture.builder.buildWhere(new QueryWrapper<>().eq("name", "x")
                .or(q -> q.eq("deletedFlagExtra", 1)));
        assertThat(where.sql()).isEqualTo(" WHERE (" + fixture.metadata.columnByName("name").name()
                + " = :p0 OR " + fixture.metadata.columnByName("deletedFlagExtra").name()
                + " = :p1) AND " + fixture.metadata.logicDeleteColumn().name() + " = :logicNotDeleteValue");
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void rejectsUnknownAliasesAndSqlFragments(DatabaseDialect dialect, boolean quote) {
        var fixture = fixture(dialect, quote);
        for (String name : List.of("u.deleted_flag", "deleted_flag AS d", "deleted_flag OR 1=1", "missing")) {
            assertThatThrownBy(() -> fixture.builder.buildWhere(new QueryWrapper<>().eq(name, 1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("实体字段不存在");
        }
        assertThatThrownBy(() -> fixture.builder.buildWhere(new QueryWrapper<>().eq("delFlg", 1)
                .or(q -> q.eq("missing", 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void derivedConditionsShareMappedColumnValidation(DatabaseDialect dialect, boolean quote) throws Exception {
        var fixture = fixture(dialect, quote);
        var resolver = new CustomMethodResolver(fixture.metadata, 1, 0);
        for (String method : List.of("findByDelFlg", "findByDELFLG", "findByDeleted_flag", "findByDeletedFlag")) {
            var parsed = resolver.resolve(Methods.class.getMethod(method, Integer.class), new Object[]{1});
            assertThat(parsed.sql()).contains(fixture.metadata.logicDeleteColumn().name() + " = :p0")
                    .doesNotContain(":logicNotDeleteValue");
            assertThat(parsed.parameters()).containsExactly(Map.entry("p0", 1));
        }
        var parsed = resolver.resolve(Methods.class.getMethod("findByDeletedFlagExtra", Integer.class),
                new Object[]{1});
        assertThat(parsed.sql()).contains(":logicNotDeleteValue");
        assertThat(parsed.parameters()).containsEntry("logicNotDeleteValue", 0);
    }

    @Test
    void entitiesWithoutLogicDeleteColumnHaveNoDefaultFilter() {
        var properties = new WcdkR2dbcProperties();
        var metadata = new RepositoryMetadata(Plain.class, properties);
        var builder = new RepositoryQuerySqlBuilder(properties, metadata, PostgreSqlDatabaseDialect.INSTANCE, null);
        assertThat(builder.buildWhere(null).sql()).isEmpty();
        assertThat(builder.buildWhere(new QueryWrapper<>().eq("name", "x")).parameters())
                .containsExactly(Map.entry("p0", "x"));
    }

    private Fixture fixture(DatabaseDialect dialect, boolean quote) {
        var properties = new WcdkR2dbcProperties();
        properties.setQuoteIdentifier(quote);
        var metadata = new RepositoryMetadata(User.class, properties, dialect);
        return new Fixture(metadata, new RepositoryQuerySqlBuilder(properties, metadata, dialect, null));
    }

    private record Fixture(RepositoryMetadata metadata, RepositoryQuerySqlBuilder builder) {}

    interface Methods {
        Flux<User> findByDelFlg(Integer value);
        Flux<User> findByDELFLG(Integer value);
        Flux<User> findByDeleted_flag(Integer value);
        Flux<User> findByDeletedFlag(Integer value);
        Flux<User> findByDeletedFlagExtra(Integer value);
    }

    static class User {
        @Column("deleted_flag") Integer delFlg;
        Integer deletedFlagExtra;
        String name;
    }

    static class Plain {
        String name;
    }
}
