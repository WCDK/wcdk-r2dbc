package com.wcdk.r2dbc.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryWrapperTests {

    @Test
    void copyCanBeLimitedWithoutMutatingCaller() {
        QueryWrapper<Object> original = new QueryWrapper<>();
        original.eq("name", "alice").orderByAsc("id");

        QueryWrapper<Object> copy = original.copy().limit(1);

        assertThat(original.limit()).isNull();
        assertThat(copy.limit()).isEqualTo(1L);
        assertThat(copy.expression()).isEqualTo(original.expression());
        assertThat(copy.orderByList()).isEqualTo(original.orderByList());
    }

    @Test
    void exposesExplicitNullAndCollectionSemantics() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>()
                .eq("name", null)
                .ne("status", null)
                .in("id", List.of())
                .notIn("tenant_id", List.of(1L, 2L))
                .isNull("deleted_at")
                .isNotNull("created_at");

        assertThat(wrapper.expression()).isInstanceOf(SqlExpression.Logical.class);
    }

    @Test
    void snapshotsInValuesAndUsesLastOrderForDuplicateColumn() {
        List<Long> ids = new ArrayList<>(List.of(1L, 2L));
        Long[] tenantIds = {3L, 4L};
        QueryWrapper<Object> wrapper = new QueryWrapper<>()
                .in("id", ids)
                .notInArray("tenant_id", tenantIds)
                .orderByAsc("id")
                .orderByDesc("id");

        ids.add(9L);
        tenantIds[0] = 8L;

        assertThat(wrapper.expression()).isNotInstanceOf(SqlExpression.Empty.class);
        assertThat(wrapper.orderByList()).containsExactly(new QueryWrapper.OrderBy("id", false));
    }

    @Test
    void validatesLongPaginationAndOverflow() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>().limit(Integer.MAX_VALUE).offset(4_000_000_000L);
        assertThat(wrapper.limit()).isEqualTo((long) Integer.MAX_VALUE);
        assertThat(wrapper.offset()).isEqualTo(4_000_000_000L);

        for (long value : new long[]{0, -1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> new QueryWrapper<>().limit(value))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new QueryWrapper<>().page(1, value))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThatThrownBy(() -> new QueryWrapper<>().offset((Integer) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryWrapper<>().page(Long.MAX_VALUE, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("溢出");
    }

    @Test
    void copyPreservesNestedAstAndCollectionSnapshotWithoutSharingBuilderChanges() {
        List<Long> ids = new ArrayList<>(List.of(1L, 2L));
        QueryWrapper<Object> original = new QueryWrapper<>().eq("status", 1)
                .or(n -> n.in("id", ids).isNull("name"));
        QueryWrapper<Object> copy = original.copy();
        SqlExpression snapshot = copy.expression();
        ids.clear();
        original.eq("tenant", 9);
        assertThat(copy.expression()).isSameAs(snapshot).isNotEqualTo(original.expression());
        var root = (SqlExpression.Logical) snapshot;
        var nested = (SqlExpression.Logical) root.operands().get(1);
        assertThat(((SqlExpression.In) nested.operands().get(0)).values()).isEqualTo(List.of(1L, 2L));
        copy.eq("tenant", 10);
        assertThat(copy.expression()).isNotEqualTo(original.expression());
    }

    @Test
    void buildsNestedPredicateAst() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>()
                .eq("status", 1)
                .or(nested -> nested.like("name", "a").eq("type", 2));

        assertThat(wrapper.expression()).isInstanceOf(SqlExpression.Logical.class);
        SqlExpression.Logical root = (SqlExpression.Logical) wrapper.expression();
        assertThat(root.operator()).isEqualTo(SqlExpression.Operator.OR);
        assertThat(root.operands().get(1)).isInstanceOf(SqlExpression.Logical.class);
        assertThat(((SqlExpression.Logical) root.operands().get(1)).operator())
                .isEqualTo(SqlExpression.Operator.AND);
    }
}
