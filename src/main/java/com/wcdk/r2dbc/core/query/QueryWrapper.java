package com.wcdk.r2dbc.core.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基础查询条件构造器。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public class QueryWrapper<T> {

    private final List<Condition> conditions = new ArrayList<>();

    private final List<OrderBy> orderByList = new ArrayList<>();

    private Long limit;

    private Long offset;

    public QueryWrapper() {
    }

    private QueryWrapper(QueryWrapper<T> source) {
        this.conditions.addAll(source.conditions);
        this.orderByList.addAll(source.orderByList);
        this.limit = source.limit;
        this.offset = source.offset;
    }

    public QueryWrapper<T> copy() {
        return new QueryWrapper<>(this);
    }

    public QueryWrapper<T> eq(String column, Object value) {
        return condition(column, "=", value);
    }

    public QueryWrapper<T> ne(String column, Object value) {
        return condition(column, "<>", value);
    }

    public QueryWrapper<T> gt(String column, Object value) {
        return condition(column, ">", value);
    }

    public QueryWrapper<T> ge(String column, Object value) {
        return condition(column, ">=", value);
    }

    public QueryWrapper<T> lt(String column, Object value) {
        return condition(column, "<", value);
    }

    public QueryWrapper<T> le(String column, Object value) {
        return condition(column, "<=", value);
    }

    public QueryWrapper<T> like(String column, Object value) {
        return condition(column, "LIKE", value);
    }

    public QueryWrapper<T> in(String column, Iterable<?> values) {
        return condition(column, "IN", values);
    }

    public QueryWrapper<T> inArray(String column, Object values) {
        return condition(column, "IN", values);
    }

    public QueryWrapper<T> notIn(String column, Iterable<?> values) {
        return condition(column, "NOT IN", values);
    }

    public QueryWrapper<T> notInArray(String column, Object values) {
        return condition(column, "NOT IN", values);
    }

    public QueryWrapper<T> isNull(String column) {
        return condition(column, "IS NULL", null);
    }

    public QueryWrapper<T> isNotNull(String column) {
        return condition(column, "IS NOT NULL", null);
    }

    public QueryWrapper<T> orderByAsc(String column) {
        return orderBy(column, true);
    }

    public QueryWrapper<T> orderByDesc(String column) {
        return orderBy(column, false);
    }

    public QueryWrapper<T> limit(int limit) {
        return limit((long) limit);
    }

    public QueryWrapper<T> limit(long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("查询条数必须大于 0");
        }
        this.limit = limit;
        return this;
    }

    public QueryWrapper<T> offset(Integer offset) {
        if (offset == null) {
            throw new IllegalArgumentException("查询偏移量不能为空");
        }
        return offset(offset.longValue());
    }

    public QueryWrapper<T> offset(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("查询偏移量不能小于 0");
        }
        this.offset = offset;
        return this;
    }

    public QueryWrapper<T> page(int pageNo, int pageSize) {
        return page((long) pageNo, (long) pageSize);
    }

    public QueryWrapper<T> page(long pageNo, long pageSize) {
        if (pageNo <= 0) {
            throw new IllegalArgumentException("页码必须大于 0");
        }
        limit(pageSize);
        try {
            return offset(Math.multiplyExact(pageNo - 1, pageSize));
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("查询页码偏移量溢出: pageNo=" + pageNo
                    + ", pageSize=" + pageSize, error);
        }
    }

    public List<Condition> conditions() {
        return Collections.unmodifiableList(conditions);
    }

    public List<OrderBy> orderByList() {
        return Collections.unmodifiableList(orderByList);
    }

    public Long limit() {
        return limit;
    }

    public Long offset() {
        return offset;
    }

    private QueryWrapper<T> condition(String column, String operator, Object value) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("查询字段不能为空");
        }
        Object snapshot = ("IN".equals(operator) || "NOT IN".equals(operator))
                ? snapshotCollection(value) : value;
        conditions.add(new Condition(column, operator, snapshot));
        return this;
    }

    private QueryWrapper<T> orderBy(String column, boolean asc) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("排序字段不能为空");
        }
        orderByList.removeIf(existing -> existing.column().equalsIgnoreCase(column));
        orderByList.add(new OrderBy(column, asc));
        return this;
    }

    private Object snapshotCollection(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Object> snapshot = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(snapshot::add);
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                snapshot.add(java.lang.reflect.Array.get(value, i));
            }
        } else {
            throw new IllegalArgumentException("IN/NOT IN值必须是可迭代对象或数组");
        }
        return Collections.unmodifiableList(snapshot);
    }

    public record Condition(String column, String operator, Object value) {
    }

    public record OrderBy(String column, boolean asc) {
    }
}
