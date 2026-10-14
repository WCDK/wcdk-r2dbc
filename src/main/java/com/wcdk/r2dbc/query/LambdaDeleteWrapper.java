package com.wcdk.r2dbc.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 历史 Lambda 条件构造器；未接入 BaseRepository 执行入口。
 * @deprecated 自 3.5.16 起弃用，计划在 4.0.0 移除。
 * 查询请使用 {@link QueryWrapper}；更新和删除请使用
 * {@link com.wcdk.r2dbc.repository.BaseRepository} 或派生方法。
 * @param <T> 实体类型
 */
@Deprecated(since = "3.5.16", forRemoval = true)
public class LambdaDeleteWrapper<T> {

    private final Class<T> entityClass;

    private final List<LambdaCondition> conditions = new ArrayList<>();

    public LambdaDeleteWrapper(Class<T> entityClass) {
        if (entityClass == null) {
            throw new IllegalArgumentException("实体类不能为空");
        }
        this.entityClass = entityClass;
    }

    /**
     * 创建 LambdaDeleteWrapper 实例。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return LambdaDeleteWrapper 实例
     */
    public static <T> LambdaDeleteWrapper<T> of(Class<T> entityClass) {
        return new LambdaDeleteWrapper<>(entityClass);
    }

    /**
     * 创建 LambdaDeleteWrapper 实例（通过实体实例推断类型）。
     *
     * @param entity 实体实例
     * @param <T>    实体类型
     * @return LambdaDeleteWrapper 实例
     */
    public static <T> LambdaDeleteWrapper<T> of(T entity) {
        if (entity == null) {
            throw new IllegalArgumentException("实体实例不能为空");
        }
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        return new LambdaDeleteWrapper<>(entityClass);
    }

    // ==================== WHERE 条件方法 ====================

    /**
     * 等值条件：column = value
     */
    public <V> LambdaDeleteWrapper<T> eq(SFunction<T, ?> field, V value) {
        return condition(field, "=", value);
    }

    /**
     * 不等条件：column &lt;&gt; value
     */
    public <V> LambdaDeleteWrapper<T> ne(SFunction<T, ?> field, V value) {
        return condition(field, "<>", value);
    }

    /**
     * 大于条件：column &gt; value
     */
    public <V> LambdaDeleteWrapper<T> gt(SFunction<T, ?> field, V value) {
        return condition(field, ">", value);
    }

    /**
     * 大于等于条件：column &gt;= value
     */
    public <V> LambdaDeleteWrapper<T> ge(SFunction<T, ?> field, V value) {
        return condition(field, ">=", value);
    }

    /**
     * 小于条件：column &lt; value
     */
    public <V> LambdaDeleteWrapper<T> lt(SFunction<T, ?> field, V value) {
        return condition(field, "<", value);
    }

    /**
     * 小于等于条件：column &lt;= value
     */
    public <V> LambdaDeleteWrapper<T> le(SFunction<T, ?> field, V value) {
        return condition(field, "<=", value);
    }

    /**
     * IN 条件：column IN (values)
     */
    @SafeVarargs
    public final <V> LambdaDeleteWrapper<T> in(SFunction<T, ?> field, V... values) {
        return condition(field, "IN", values);
    }

    /**
     * BETWEEN 条件：column BETWEEN start AND end
     */
    public <V> LambdaDeleteWrapper<T> between(SFunction<T, ?> field, V start, V end) {
        String column = resolveColumn(field);
        conditions.add(new LambdaCondition(column, "BETWEEN", new Object[]{start, end}));
        return this;
    }

    /**
     * IS NULL 条件：column IS NULL
     */
    public LambdaDeleteWrapper<T> isNull(SFunction<T, ?> field) {
        return condition(field, "IS NULL", null);
    }

    /**
     * IS NOT NULL 条件：column IS NOT NULL
     */
    public LambdaDeleteWrapper<T> isNotNull(SFunction<T, ?> field) {
        return condition(field, "IS NOT NULL", null);
    }

    // ==================== 获取结果 ====================

    /**
     * 获取 WHERE 条件列表
     */
    public List<LambdaCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }

    /**
     * 获取实体类
     */
    public Class<T> entityClass() {
        return entityClass;
    }

    /**
     * 是否有 WHERE 条件
     */
    public boolean hasConditions() {
        return !conditions.isEmpty();
    }

    // ==================== 内部方法 ====================

    private <V> LambdaDeleteWrapper<T> condition(SFunction<T, ?> field, String operator, V value) {
        String column = resolveColumn(field);
        conditions.add(new LambdaCondition(column, operator, value));
        return this;
    }

    /**
     * 解析 Lambda 表达式对应的数据库列名。
     */
    private String resolveColumn(SFunction<T, ?> field) {
        return LambdaPropertyResolver.defaultResolver()
                .resolve(field, LambdaPropertyResolver.metadata(entityClass)).name();
    }

    // ==================== 类型定义 ====================

    /**
     * 函数式接口，支持方法引用。
     */
    @FunctionalInterface
    public interface SFunction<T, R> extends Function<T, R>, Serializable {
    }

    /**
     * 条件定义。
     */
    public record LambdaCondition(String column, String operator, Object value) {
    }
}
