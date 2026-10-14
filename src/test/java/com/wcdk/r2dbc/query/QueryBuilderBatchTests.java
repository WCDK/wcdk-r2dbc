package com.wcdk.r2dbc.query;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.dialect.PostgreSqlDatabaseDialect;
import com.wcdk.r2dbc.execution.SqlParameter;
import com.wcdk.r2dbc.query.sql.SqlExpressionRenderer;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SuppressWarnings("removal")
class QueryBuilderBatchTests {
    static class Entity {
        @Id Long id;
        @Column("URL_Value") String URLValue;
        public String getURLValue() { return URLValue; }
    }
    private SqlExpressionRenderer renderer() {
        var properties = new WcdkR2dbcProperties(); properties.setQuoteIdentifier(false);
        return new SqlExpressionRenderer(new RepositoryMetadata(Entity.class, properties, PostgreSqlDatabaseDialect.INSTANCE));
    }
    @Test void nullInValuesAreSnapshottedAndRenderedAsTypedNulls() {
        var values = new ArrayList<Long>(Arrays.asList(1L, null));
        var wrapper = new QueryWrapper<Entity>().in("id", values);
        values.clear();
        var in = (SqlExpression.In) wrapper.expression();
        assertThat(in.values()).isEqualTo(Arrays.asList(1L, null));
        assertThatThrownBy(()->in.values().clear()).isInstanceOf(UnsupportedOperationException.class);
        var rendered = renderer().render(in);
        assertThat(rendered.sql()).isEqualTo(" WHERE id IN (:p0, :p1)");
        assertThat(rendered.bindings()).containsEntry("p0", 1L).containsEntry("p1", SqlParameter.nullOf(Long.class));
        var notIn = renderer().render(new QueryWrapper<Entity>().notInArray("id", new Long[]{1L, null}).expression());
        assertThat(notIn.sql()).isEqualTo(" WHERE id NOT IN (:p0, :p1)");
        assertThat(notIn.bindings().get("p1")).isEqualTo(SqlParameter.nullOf(Long.class));
    }
    @Test void reusedRendererHasIsolatedBindingStateAndRestartsParameterNames() {
        var renderer = renderer();
        var first = renderer.render(new QueryWrapper<Entity>().eq("id", 1L).or(n->n.in("id", List.of(2L,3L))).expression());
        var second = renderer.render(new QueryWrapper<Entity>().eq("URLValue", "value").expression());
        assertThat(first.bindings()).isEqualTo(Map.of("p0",1L,"p1",2L,"p2",3L));
        assertThat(second.sql()).isEqualTo(" WHERE URL_Value = :p0");
        assertThat(second.bindings()).containsOnlyKeys("p0").containsEntry("p0","value");
        assertThat(renderer.render(new SqlExpression.Empty()).bindings()).isEmpty();
    }
    @Test void paginationErrorsPreservePreviouslyCommittedState() {
        var wrapper = new QueryWrapper<Entity>().page(3, 10);
        assertThatThrownBy(()->wrapper.page(Long.MAX_VALUE, 2)).hasMessageContaining("分页偏移量计算溢出");
        assertThat(wrapper.limit()).isEqualTo(10L); assertThat(wrapper.offset()).isEqualTo(20L);
        assertThatThrownBy(()->wrapper.page(1, (long)Integer.MAX_VALUE+1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wrapper.limit()).isEqualTo(10L); assertThat(wrapper.offset()).isEqualTo(20L);
        var legacy = LambdaQueryWrapper.of(Entity.class).page(3,10);
        assertThatThrownBy(()->legacy.page(Integer.MAX_VALUE,2)).hasMessageContaining("分页偏移量计算溢出");
        assertThat(legacy.limit()).isEqualTo(10); assertThat(legacy.offset()).isEqualTo(20);
    }
    @Test void lambdaWrappersShareMetadataAndRespectMappedAcronymColumns() {
        var query = LambdaQueryWrapper.of(Entity.class).orderByAsc(Entity::getURLValue);
        var update = LambdaUpdateWrapper.of(Entity.class).set(Entity::getURLValue,"value");
        var delete = LambdaDeleteWrapper.of(Entity.class).eq(Entity::getURLValue,"value");
        assertThat(query.orderByList().get(0).column()).isEqualTo("URL_Value");
        assertThat(update.getSetValues()).containsOnlyKeys("URL_Value");
        assertThat(delete.getConditions().get(0).column()).isEqualTo("URL_Value");
        assertThat(LambdaPropertyResolver.metadata(Entity.class)).isSameAs(LambdaPropertyResolver.metadata(Entity.class));
    }
}
