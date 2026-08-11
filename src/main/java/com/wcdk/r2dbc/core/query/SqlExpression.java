package com.wcdk.r2dbc.core.query;

import java.util.List;
import java.util.Objects;

/***
 * SQL谓词抽象语法树。
 * @author wcdk
 */
public sealed interface SqlExpression permits SqlExpression.Empty, SqlExpression.Comparison,
        SqlExpression.In, SqlExpression.NullCheck, SqlExpression.Logical {

    record Empty() implements SqlExpression {
    }

    record Comparison(String column, String operator, Object value) implements SqlExpression {
        public Comparison {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(operator, "operator");
        }
    }

    record In(String column, boolean negated, List<?> values) implements SqlExpression {
        public In {
            Objects.requireNonNull(column, "column");
            values = List.copyOf(values);
        }
    }

    record NullCheck(String column, boolean negated) implements SqlExpression {
        public NullCheck {
            Objects.requireNonNull(column, "column");
        }
    }

    record Logical(Operator operator, List<SqlExpression> operands) implements SqlExpression {
        public Logical {
            Objects.requireNonNull(operator, "operator");
            operands = List.copyOf(operands);
            if (operands.isEmpty()) {
                throw new IllegalArgumentException("逻辑表达式需要操作数");
            }
        }
    }

    enum Operator {
        AND, OR
    }
}