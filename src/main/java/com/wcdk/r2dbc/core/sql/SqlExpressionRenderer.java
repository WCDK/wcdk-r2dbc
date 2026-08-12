package com.wcdk.r2dbc.core.sql;

import com.wcdk.r2dbc.core.executor.SqlParameter;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata.FieldColumn;
import com.wcdk.r2dbc.core.query.SqlExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/***
 * Query AST SQL 渲染器。
 * @author wcdk
 */
public final class SqlExpressionRenderer {
    private final RepositoryMetadata metadata;
    private final Map<String, Object> bindings = new LinkedHashMap<>();
    private int index;

    public SqlExpressionRenderer(RepositoryMetadata metadata) {
        this.metadata = metadata;
    }

    public RenderedPredicate render(SqlExpression expression) {
        if (expression == null || expression instanceof SqlExpression.Empty) {
            return RenderedPredicate.empty();
        }
        String sql = renderExpression(expression);
        return new RenderedPredicate(sql.isBlank() ? "" : " WHERE " + sql, bindings);
    }

    private String renderExpression(SqlExpression expression) {
        return switch (expression) {
            case SqlExpression.Empty ignored -> "";
            case SqlExpression.Comparison comparison -> renderComparison(comparison);
            case SqlExpression.In in -> renderIn(in);
            case SqlExpression.NullCheck check -> column(check.column()).name()
                    + (check.negated() ? " IS NOT NULL" : " IS NULL");
            case SqlExpression.Logical logical -> renderLogical(logical);
        };
    }

    private String renderComparison(SqlExpression.Comparison comparison) {
        FieldColumn column = column(comparison.column());
        if (comparison.value() == null
                && ("=".equals(comparison.operator()) || "<>".equals(comparison.operator()))) {
            return column.name() + ("=".equals(comparison.operator()) ? " IS NULL" : " IS NOT NULL");
        }
        String parameter = "p" + index++;
        bindings.put(parameter, typedNull(comparison.value(), column));
        return column.name() + " " + comparison.operator() + " :" + parameter;
    }

    private String renderIn(SqlExpression.In in) {
        FieldColumn column = column(in.column());
        if (in.values().isEmpty()) {
            return in.negated() ? "1 = 1" : "1 = 0";
        }
        List<String> parameters = new ArrayList<>();
        for (Object value : in.values()) {
            String parameter = "p" + index++;
            parameters.add(":" + parameter);
            bindings.put(parameter, typedNull(value, column));
        }
        return column.name() + (in.negated() ? " NOT IN (" : " IN (")
                + String.join(", ", parameters) + ")";
    }

    private String renderLogical(SqlExpression.Logical logical) {
        List<String> operands = logical.operands().stream()
                .map(this::renderExpression)
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
