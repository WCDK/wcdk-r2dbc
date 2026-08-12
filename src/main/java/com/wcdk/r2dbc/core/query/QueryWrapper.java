package com.wcdk.r2dbc.core.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/***
 * 基础查询条件构造器。
 * @author wcdk
 */
public class QueryWrapper<T> {
    private final List<Condition> conditions = new ArrayList<>();
    private final List<OrderBy> orderByList = new ArrayList<>();
    private SqlExpression expression = new SqlExpression.Empty();
    private Long limit;
    private Long offset;

    public QueryWrapper() {
    }

    private QueryWrapper(QueryWrapper<T> source) {
        conditions.addAll(source.conditions);
        orderByList.addAll(source.orderByList);
        expression = source.expression;
        limit = source.limit;
        offset = source.offset;
    }

    public QueryWrapper<T> copy() { return new QueryWrapper<>(this); }
    public QueryWrapper<T> eq(String column, Object value) { return condition(column, "=", value); }
    public QueryWrapper<T> ne(String column, Object value) { return condition(column, "<>", value); }
    public QueryWrapper<T> gt(String column, Object value) { return condition(column, ">", value); }
    public QueryWrapper<T> ge(String column, Object value) { return condition(column, ">=", value); }
    public QueryWrapper<T> lt(String column, Object value) { return condition(column, "<", value); }
    public QueryWrapper<T> le(String column, Object value) { return condition(column, "<=", value); }
    public QueryWrapper<T> like(String column, Object value) { return condition(column, "LIKE", value); }
    public QueryWrapper<T> in(String column, Iterable<?> values) { return condition(column, "IN", values); }
    public QueryWrapper<T> inArray(String column, Object values) { return condition(column, "IN", values); }
    public QueryWrapper<T> notIn(String column, Iterable<?> values) { return condition(column, "NOT IN", values); }
    public QueryWrapper<T> notInArray(String column, Object values) { return condition(column, "NOT IN", values); }
    public QueryWrapper<T> isNull(String column) { return condition(column, "IS NULL", null); }
    public QueryWrapper<T> isNotNull(String column) { return condition(column, "IS NOT NULL", null); }

    public QueryWrapper<T> and(Consumer<QueryWrapper<T>> nested) { return logical(SqlExpression.Operator.AND, nested); }
    public QueryWrapper<T> or(Consumer<QueryWrapper<T>> nested) { return logical(SqlExpression.Operator.OR, nested); }
    public SqlExpression expression() { return expression; }

    public QueryWrapper<T> orderByAsc(String column) { return orderBy(column, true); }
    public QueryWrapper<T> orderByDesc(String column) { return orderBy(column, false); }
    public QueryWrapper<T> limit(int value) { return limit((long) value); }
    public QueryWrapper<T> limit(long value) { if (value <= 0) throw new IllegalArgumentException("limit must be greater than 0"); limit = value; return this; }
    public QueryWrapper<T> offset(Integer value) { if (value == null) throw new IllegalArgumentException("offset cannot be null"); return offset(value.longValue()); }
    public QueryWrapper<T> offset(long value) { if (value < 0) throw new IllegalArgumentException("offset cannot be negative"); offset = value; return this; }
    public QueryWrapper<T> page(int pageNo, int pageSize) { return page((long) pageNo, (long) pageSize); }
    public QueryWrapper<T> page(long pageNo, long pageSize) {
        if (pageNo <= 0) throw new IllegalArgumentException("page number must be greater than 0");
        limit(pageSize);
        try { return offset(Math.multiplyExact(pageNo - 1, pageSize)); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("pagination offset overflow", error); }
    }

    @Deprecated
    public List<Condition> conditions() { return Collections.unmodifiableList(conditions); }
    public List<OrderBy> orderByList() { return Collections.unmodifiableList(orderByList); }
    public Long limit() { return limit; }
    public Long offset() { return offset; }

    private QueryWrapper<T> condition(String column, String operator, Object value) {
        if (column == null || column.isBlank()) throw new IllegalArgumentException("column cannot be blank");
        Object snapshot = ("IN".equals(operator) || "NOT IN".equals(operator)) ? snapshotCollection(value) : value;
        conditions.add(new Condition(column, operator, snapshot));
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
        if (nested == null) throw new IllegalArgumentException("nested expression cannot be null");
        QueryWrapper<T> child = new QueryWrapper<>();
        nested.accept(child);
        if (!(child.expression() instanceof SqlExpression.Empty)) expression = append(child.expression(), operator);
        return this;
    }

    private SqlExpression append(SqlExpression next, SqlExpression.Operator operator) {
        if (expression instanceof SqlExpression.Empty) return next;
        if (expression instanceof SqlExpression.Logical logical && logical.operator() == operator) {
            List<SqlExpression> operands = new ArrayList<>(logical.operands());
            operands.add(next);
            return new SqlExpression.Logical(operator, operands);
        }
        return new SqlExpression.Logical(operator, List.of(expression, next));
    }

    private QueryWrapper<T> orderBy(String column, boolean asc) {
        if (column == null || column.isBlank()) throw new IllegalArgumentException("order column cannot be blank");
        orderByList.removeIf(existing -> existing.column().equalsIgnoreCase(column));
        orderByList.add(new OrderBy(column, asc));
        return this;
    }

    private Object snapshotCollection(Object value) {
        if (value == null) return List.of();
        List<Object> snapshot = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) iterable.forEach(snapshot::add);
        else if (value.getClass().isArray()) for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) snapshot.add(java.lang.reflect.Array.get(value, i));
        else throw new IllegalArgumentException("IN/NOT IN value must be iterable or array");
        return Collections.unmodifiableList(snapshot);
    }

    public record Condition(String column, String operator, Object value) { }
    public record OrderBy(String column, boolean asc) { }
}