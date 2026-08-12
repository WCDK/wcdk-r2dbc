package com.wcdk.r2dbc.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/***
 * 基础查询条件构造器。
 * @author wcdk
 **/
public class QueryWrapper<T> {

    private final List<Condition> conditions = new ArrayList<>();
    private final List<OrderBy> orderByList = new ArrayList<>();
    private SqlExpression expression = new SqlExpression.Empty();
    private Long limit;
    private Long offset;

    /***
     * 创建空查询条件构造器。
     * @author wcdk
     **/
    public QueryWrapper() {
    }

    private QueryWrapper(QueryWrapper<T> source) {
        conditions.addAll(source.conditions);
        orderByList.addAll(source.orderByList);
        expression = source.expression;
        limit = source.limit;
        offset = source.offset;
    }

    /***
     * 复制当前查询条件构造器。
     * @author wcdk
     * @return 查询条件副本
     **/
    public QueryWrapper<T> copy() {
        return new QueryWrapper<>(this);
    }

    /*** 添加等于条件。 @author wcdk **/
    public QueryWrapper<T> eq(String column, Object value) {
        return condition(column, "=", value);
    }

    /*** 添加不等于条件。 @author wcdk **/
    public QueryWrapper<T> ne(String column, Object value) {
        return condition(column, "<>", value);
    }

    /*** 添加大于条件。 @author wcdk **/
    public QueryWrapper<T> gt(String column, Object value) {
        return condition(column, ">", value);
    }

    /*** 添加大于等于条件。 @author wcdk **/
    public QueryWrapper<T> ge(String column, Object value) {
        return condition(column, ">=", value);
    }

    /*** 添加小于条件。 @author wcdk **/
    public QueryWrapper<T> lt(String column, Object value) {
        return condition(column, "<", value);
    }

    /*** 添加小于等于条件。 @author wcdk **/
    public QueryWrapper<T> le(String column, Object value) {
        return condition(column, "<=", value);
    }

    /*** 添加模糊匹配条件。 @author wcdk **/
    public QueryWrapper<T> like(String column, Object value) {
        return condition(column, "LIKE", value);
    }

    /*** 添加集合包含条件。 @author wcdk **/
    public QueryWrapper<T> in(String column, Iterable<?> values) {
        return condition(column, "IN", values);
    }

    /*** 添加数组包含条件。 @author wcdk **/
    public QueryWrapper<T> inArray(String column, Object values) {
        return condition(column, "IN", values);
    }

    /*** 添加集合不包含条件。 @author wcdk **/
    public QueryWrapper<T> notIn(String column, Iterable<?> values) {
        return condition(column, "NOT IN", values);
    }

    /*** 添加数组不包含条件。 @author wcdk **/
    public QueryWrapper<T> notInArray(String column, Object values) {
        return condition(column, "NOT IN", values);
    }

    /*** 添加为空条件。 @author wcdk **/
    public QueryWrapper<T> isNull(String column) {
        return condition(column, "IS NULL", null);
    }

    /*** 添加不为空条件。 @author wcdk **/
    public QueryWrapper<T> isNotNull(String column) {
        return condition(column, "IS NOT NULL", null);
    }

    /***
     * 添加嵌套 AND 条件。
     * @author wcdk
     * @param nested 嵌套条件构造逻辑
     * @return 当前查询条件构造器
     **/
    public QueryWrapper<T> and(Consumer<QueryWrapper<T>> nested) {
        return logical(SqlExpression.Operator.AND, nested);
    }

    /***
     * 添加嵌套 OR 条件。
     * @author wcdk
     * @param nested 嵌套条件构造逻辑
     * @return 当前查询条件构造器
     **/
    public QueryWrapper<T> or(Consumer<QueryWrapper<T>> nested) {
        return logical(SqlExpression.Operator.OR, nested);
    }

    /***
     * 获取当前条件表达式 AST。
     * @author wcdk
     * @return SQL 条件表达式
     **/
    public SqlExpression expression() {
        return expression;
    }

    /*** 按字段升序排序。 @author wcdk **/
    public QueryWrapper<T> orderByAsc(String column) {
        return orderBy(column, true);
    }

    /*** 按字段降序排序。 @author wcdk **/
    public QueryWrapper<T> orderByDesc(String column) {
        return orderBy(column, false);
    }

    /*** 设置查询条数。 @author wcdk **/
    public QueryWrapper<T> limit(int value) {
        return limit((long) value);
    }

    /***
     * 设置查询条数。
     * @author wcdk
     * @param value 查询条数，必须大于 0
     * @return 当前查询条件构造器
     **/
    public QueryWrapper<T> limit(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("查询条数必须大于0");
        }
        limit = value;
        return this;
    }

    /*** 设置查询偏移量。 @author wcdk **/
    public QueryWrapper<T> offset(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("查询偏移量不能为空");
        }
        return offset(value.longValue());
    }

    /***
     * 设置查询偏移量。
     * @author wcdk
     * @param value 查询偏移量，不能小于 0
     * @return 当前查询条件构造器
     **/
    public QueryWrapper<T> offset(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("查询偏移量不能小于0");
        }
        offset = value;
        return this;
    }

    /*** 使用从 1 开始的页码设置分页参数。 @author wcdk **/
    public QueryWrapper<T> page(int pageNo, int pageSize) {
        return page((long) pageNo, (long) pageSize);
    }

    /***
     * 使用从 1 开始的页码设置分页参数。
     * @author wcdk
     * @param pageNo 页码，必须大于 0
     * @param pageSize 页面大小，必须大于 0
     * @return 当前查询条件构造器
     **/
    public QueryWrapper<T> page(long pageNo, long pageSize) {
        if (pageNo <= 0) {
            throw new IllegalArgumentException("页码必须大于0");
        }
        limit(pageSize);
        try {
            return offset(Math.multiplyExact(pageNo - 1, pageSize));
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("分页偏移量计算溢出", error);
        }
    }

    /***
     * 获取历史条件列表。
     * @author wcdk
     * @return 只读条件列表
     * @deprecated 请使用 {@link #expression()}，条件表达式 AST 是唯一执行来源。
     **/
    @Deprecated
    public List<Condition> conditions() {
        return Collections.unmodifiableList(conditions);
    }

    /*** 获取只读排序条件列表。 @author wcdk **/
    public List<OrderBy> orderByList() {
        return Collections.unmodifiableList(orderByList);
    }

    /*** 获取查询条数。 @author wcdk **/
    public Long limit() {
        return limit;
    }

    /*** 获取查询偏移量。 @author wcdk **/
    public Long offset() {
        return offset;
    }

    private QueryWrapper<T> condition(String column, String operator, Object value) {
        validateColumn(column, "条件字段");
        Object snapshot = ("IN".equals(operator) || "NOT IN".equals(operator))
                ? snapshotCollection(value) : value;
        conditions.add(new Condition(column, operator, snapshot));

        // 同步更新条件兼容列表和唯一执行来源 AST。
        if ("IN".equals(operator) || "NOT IN".equals(operator)) {
            expression = append(new SqlExpression.In(column, "NOT IN".equals(operator), (List<?>) snapshot), SqlExpression.Operator.AND);
        } else if ("IS NULL".equals(operator) || "IS NOT NULL".equals(operator)) {
            expression = append(new SqlExpression.NullCheck(column, "IS NOT NULL".equals(operator)), SqlExpression.Operator.AND);
        } else {
            expression = append(new SqlExpression.Comparison(column, operator, snapshot), SqlExpression.Operator.AND);
        }
        return this;
    }

    private QueryWrapper<T> logical(SqlExpression.Operator operator, Consumer<QueryWrapper<T>> nested) {
        if (nested == null) {
            throw new IllegalArgumentException("嵌套条件构造器不能为空");
        }
        QueryWrapper<T> child = new QueryWrapper<>();
        nested.accept(child);
        if (!(child.expression() instanceof SqlExpression.Empty)) {
            expression = append(child.expression(), operator);
        }
        return this;
    }

    private SqlExpression append(SqlExpression next, SqlExpression.Operator operator) {
        if (expression instanceof SqlExpression.Empty) {
            return next;
        }
        if (expression instanceof SqlExpression.Logical logical && logical.operator() == operator) {
            List<SqlExpression> operands = new ArrayList<>(logical.operands());
            operands.add(next);
            return new SqlExpression.Logical(operator, operands);
        }
        return new SqlExpression.Logical(operator, List.of(expression, next));
    }

    private QueryWrapper<T> orderBy(String column, boolean asc) {
        validateColumn(column, "排序字段");
        orderByList.removeIf(existing -> existing.column().equalsIgnoreCase(column));
        orderByList.add(new OrderBy(column, asc));
        return this;
    }

    private void validateColumn(String column, String description) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException(description + "不能为空");
        }
    }

    private Object snapshotCollection(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Object> snapshot = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(snapshot::add);
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                snapshot.add(java.lang.reflect.Array.get(value, i));
            }
        } else {
            throw new IllegalArgumentException("IN和NOT IN条件的值必须是集合或数组");
        }
        return Collections.unmodifiableList(snapshot);
    }

    /*** 查询条件记录。 @author wcdk **/
    public record Condition(String column, String operator, Object value) {
    }

    /*** 排序条件记录。 @author wcdk **/
    public record OrderBy(String column, boolean asc) {
    }
}