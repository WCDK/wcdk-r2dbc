package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.dialect.*;
import com.wcdk.r2dbc.query.QueryWrapper;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.relational.core.mapping.Column;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageableSortTests {
    static Stream<Object[]> configurations() {
        return Stream.<DatabaseDialect>of(MySqlDatabaseDialect.INSTANCE, PostgreSqlDatabaseDialect.INSTANCE,
                        OracleDatabaseDialect.INSTANCE, DmDatabaseDialect.INSTANCE)
                .flatMap(dialect -> Stream.of(new Object[]{dialect, true}, new Object[]{dialect, false}));
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void mapsAscendingDescendingAndMultipleFields(DatabaseDialect dialect, boolean quote) {
        var builder = builder(dialect, quote);
        String name = builder.metadata.columnByName("displayName").name();
        String id = builder.metadata.columnByName("id").name();
        assertThat(builder.orderBySql(PageRequest.of(0, 5, Sort.by("displayName")), null))
                .isEqualTo(" ORDER BY " + name + " ASC");
        assertThat(builder.orderBySql(PageRequest.of(0, 5, Sort.by(Sort.Order.desc("displayName"))), null))
                .isEqualTo(" ORDER BY " + name + " DESC");
        assertThat(builder.orderBySql(PageRequest.of(0, 5,
                        Sort.by(Sort.Order.desc("display_name"), Sort.Order.asc("id"))), new QueryWrapper<>()))
                .isEqualTo(" ORDER BY " + name + " DESC, " + id + " ASC");
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void pageableWinsDuplicateColumnsAndWrapperAddsTieBreakers(DatabaseDialect dialect, boolean quote) {
        var builder = builder(dialect, quote);
        var wrapper = new QueryWrapper<>().orderByAsc("displayName").orderByDesc("id");
        var original = List.copyOf(wrapper.orderByList());
        var pageable = PageRequest.of(0, 5, Sort.by(Sort.Order.desc("display_name"), Sort.Order.asc("DISPLAYNAME")));
        assertThat(builder.orderBySql(pageable, wrapper)).isEqualTo(" ORDER BY "
                + builder.metadata.columnByName("displayName").name() + " DESC, "
                + builder.metadata.columnByName("id").name() + " DESC");
        assertThat(wrapper.orderByList()).containsExactlyElementsOf(original);
        assertThat(pageable.getSort()).hasSize(2);
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void unsortedPageableKeepsWrapperAndNoSortGeneratesNoClause(DatabaseDialect dialect, boolean quote) {
        var builder = builder(dialect, quote);
        var pageable = PageRequest.of(0, 5);
        assertThat(builder.orderBySql(pageable, null)).isEmpty();
        assertThat(builder.orderBySql(pageable, new QueryWrapper<>())).isEmpty();
        assertThat(builder.orderBySql(pageable, new QueryWrapper<>().orderByDesc("id")))
                .isEqualTo(" ORDER BY " + builder.metadata.columnByName("id").name() + " DESC");
        assertThat(builder.orderBySql(Pageable.unpaged(Sort.by("id")), null))
                .isEqualTo(" ORDER BY " + builder.metadata.columnByName("id").name() + " ASC");
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void rejectsInvalidPropertiesFromBothSources(DatabaseDialect dialect, boolean quote) {
        var builder = builder(dialect, quote);
        for (String property : List.of("missing", "marker", "u.id", "id DESC; DROP TABLE users", "LOWER(display_name)")) {
            assertThatThrownBy(() -> builder.orderBySql(PageRequest.of(0, 5, Sort.by(property)),
                    new QueryWrapper<>().orderByAsc("id")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("实体字段不存在");
            assertThatThrownBy(() -> builder.orderBySql(PageRequest.of(0, 5, Sort.by("id")),
                    new QueryWrapper<>().orderByAsc(property)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("实体字段不存在");
        }
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void rejectsUnsupportedOptionsEvenOnDuplicateColumns(DatabaseDialect dialect, boolean quote) {
        var builder = builder(dialect, quote);
        for (Sort.Order order : List.of(Sort.Order.asc("displayName").ignoreCase(),
                Sort.Order.asc("displayName").nullsFirst(), Sort.Order.desc("displayName").nullsLast())) {
            assertThatThrownBy(() -> builder.orderBySql(
                    PageRequest.of(0, 5, Sort.by(Sort.Order.asc("displayName"), order)), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("分页排序仅支持");
        }
    }

    private RepositoryQuerySqlBuilder builder(DatabaseDialect dialect, boolean quote) {
        var properties = new WcdkR2dbcProperties();
        properties.setQuoteIdentifier(quote);
        return new RepositoryQuerySqlBuilder(properties, new RepositoryMetadata(User.class, properties, dialect),
                dialect, null);
    }

    static class User {
        Long id;
        @Column("display_name") String displayName;
        @Transient String marker;
    }
}
