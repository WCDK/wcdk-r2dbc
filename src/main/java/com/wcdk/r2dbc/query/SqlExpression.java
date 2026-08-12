package com.wcdk.r2dbc.query;

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
            Objects.requireNonNull(column, "字段名不能为空");
            Objects.requireNonNull(operator, "操作符不能为空");
        }
    }

    record In(String column, boolean negated, List<?> values) implements SqlExpression {
        public In {
            Objects.requireNonNull(column, "字段名不能为空");
            values = List.copyOf(values);
        }
    }

    record NullCheck(String column, boolean negated) implements SqlExpression {
        public NullCheck {
            Objects.requireNonNull(column, "字段名不能为空");
        }
    }

    record Logical(Operator operator, List<SqlExpression> operands) implements SqlExpression {
        public Logical {
            Objects.requireNonNull(operator, "操作符不能为空");
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