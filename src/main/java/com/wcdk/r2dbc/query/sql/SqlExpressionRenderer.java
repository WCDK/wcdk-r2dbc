package com.wcdk.r2dbc.query.sql;

import com.wcdk.r2dbc.execution.SqlParameter;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata.FieldColumn;
import com.wcdk.r2dbc.query.SqlExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query AST SQL 渲染器。
 * @author wcdk
 */
public final class SqlExpressionRenderer {
    private final RepositoryMetadata metadata;
    private static final class RenderState {
        private final Map<String, Object> bindings = new LinkedHashMap<>();
        private int index;
    }

    public SqlExpressionRenderer(RepositoryMetadata metadata) {
        this.metadata = metadata;
    }

    public RenderedPredicate render(SqlExpression expression) {
        if (expression == null || expression instanceof SqlExpression.Empty) {
            return RenderedPredicate.empty();
        }
        RenderState state = new RenderState();
        String sql = renderExpression(expression, state);
        return new RenderedPredicate(sql.isBlank() ? "" : " WHERE " + sql, state.bindings);
    }

    /**
     * 基于实体字段白名单判断 AST 是否显式引用指定列。
     * @author wcdk
     */
    public boolean referencesColumn(SqlExpression expression, FieldColumn target) {
        if (expression == null) {
            return false;
        }
        return switch (expression) {
            case SqlExpression.Empty ignored -> false;
            case SqlExpression.Comparison comparison -> column(comparison.column()).equals(target);
            case SqlExpression.In in -> column(in.column()).equals(target);
            case SqlExpression.NullCheck check -> column(check.column()).equals(target);
            case SqlExpression.Logical logical -> logical.operands().stream()
                    .anyMatch(operand -> referencesColumn(operand, target));
        };
    }

    private String renderExpression(SqlExpression expression, RenderState state) {
        return switch (expression) {
            case SqlExpression.Empty ignored -> "";
            case SqlExpression.Comparison comparison -> renderComparison(comparison, state);
            case SqlExpression.In in -> renderIn(in, state);
            case SqlExpression.NullCheck check -> column(check.column()).name()
                    + (check.negated() ? " IS NOT NULL" : " IS NULL");
            case SqlExpression.Logical logical -> renderLogical(logical, state);
        };
    }

    private String renderComparison(SqlExpression.Comparison comparison, RenderState state) {
        FieldColumn column = column(comparison.column());
        if (comparison.value() == null
                && ("=".equals(comparison.operator()) || "<>".equals(comparison.operator()))) {
            return column.name() + ("=".equals(comparison.operator()) ? " IS NULL" : " IS NOT NULL");
        }
        String parameter = "p" + state.index++;
        state.bindings.put(parameter, typedNull(comparison.value(), column));
        return column.name() + " " + comparison.operator() + " :" + parameter;
    }

    private String renderIn(SqlExpression.In in, RenderState state) {
        FieldColumn column = column(in.column());
        if (in.values().isEmpty()) {
            return in.negated() ? "1 = 1" : "1 = 0";
        }
        List<String> parameters = new ArrayList<>();
        for (Object value : in.values()) {
            String parameter = "p" + state.index++;
            parameters.add(":" + parameter);
            state.bindings.put(parameter, typedNull(value, column));
        }
        return column.name() + (in.negated() ? " NOT IN (" : " IN (")
                + String.join(", ", parameters) + ")";
    }

    private String renderLogical(SqlExpression.Logical logical, RenderState state) {
        List<String> operands = logical.operands().stream()
                .map(operand -> renderExpression(operand, state))
                .filter(sql -> !sql.isBlank())
                .toList();
        if (operands.isEmpty()) {
            return "";
        }
        if (operands.size() == 1) {
            return operands.get(0);
        }
        return "(" + String.join(" " + logical.operator().name() + " ", operands) + ")";
    }

    private FieldColumn column(String name) {
        return metadata.columnByName(name);
    }

    private Object typedNull(Object value, FieldColumn column) {
        return value == null ? SqlParameter.nullOf(column.field().getType()) : value;
    }
}
