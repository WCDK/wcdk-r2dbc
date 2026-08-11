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

    private Integer limit;

    private Integer offset;

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

    public QueryWrapper<T> notIn(String column, Iterable<?> values) {
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
        if (limit <= 0) {
            throw new IllegalArgumentException("查询条数必须大于 0");
        }
        this.limit = limit;
        return this;
    }

    public QueryWrapper<T> offset(Integer offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("查询偏移量不能小于 0");
        }
        this.offset = offset;
        return this;
    }

    public QueryWrapper<T> page(int pageNo, int pageSize) {
        if (pageNo <= 0) {
            throw new IllegalArgumentException("页码必须大于 0");
        }
        limit(pageSize);
        return offset((pageNo - 1) * pageSize);
    }

    public List<Condition> conditions() {
        return Collections.unmodifiableList(conditions);
    }

    public List<OrderBy> orderByList() {
        return Collections.unmodifiableList(orderByList);
    }

    public Integer limit() {
        return limit;
    }

    public Integer offset() {
        return offset;
    }

    private QueryWrapper<T> condition(String column, String operator, Object value) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("查询字段不能为空");
        }
        conditions.add(new Condition(column, operator, value));
        return this;
    }

    private QueryWrapper<T> orderBy(String column, boolean asc) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("排序字段不能为空");
        }
        orderByList.add(new OrderBy(column, asc));
        return this;
    }

    public record Condition(String column, String operator, Object value) {
    }

    public record OrderBy(String column, boolean asc) {
    }
}
