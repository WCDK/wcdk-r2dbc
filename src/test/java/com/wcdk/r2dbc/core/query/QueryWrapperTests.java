package com.wcdk.r2dbc.core.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryWrapperTests {

    @Test
    void copyCanBeLimitedWithoutMutatingCaller() {
        QueryWrapper<Object> original = new QueryWrapper<>();
        original.eq("name", "alice").orderByAsc("id");

        QueryWrapper<Object> copy = original.copy().limit(1);

        assertThat(original.limit()).isNull();
        assertThat(copy.limit()).isEqualTo(1);
        assertThat(copy.conditions()).isEqualTo(original.conditions());
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

        assertThat(wrapper.conditions()).extracting(QueryWrapper.Condition::operator)
                .containsExactly("=", "<>", "IN", "NOT IN", "IS NULL", "IS NOT NULL");
    }
}
