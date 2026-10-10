package com.wcdk.r2dbc.query;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
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
        String fieldName = resolveFieldName(field);
        return camelToUnderline(fieldName);
    }

    /**
     * 解析 Lambda 表达式对应的字段名。
     */
    private String resolveFieldName(SFunction<T, ?> field) {
        Method writeMethod = getWriteMethod(field);
        String methodName = writeMethod.getName();

        if (methodName.startsWith("get") && methodName.length() > 3) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        if (methodName.startsWith("set") && methodName.length() > 3) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }

        return methodName;
    }

    /**
     * 获取 Lambda 表达式对应的方法。
     */
    private Method getWriteMethod(SFunction<T, ?> field) {
        try {
            if (field instanceof Serializable serializable) {
                Method writeMethod = serializable.getClass().getDeclaredMethod("writeReplace");
                writeMethod.setAccessible(true);
                SerializedLambda lambda = (SerializedLambda) writeMethod.invoke(serializable);
                String implMethodName = lambda.getImplMethodName();
                return findMethod(entityClass, implMethodName);
            }
        } catch (Exception ignored) {
        }

        throw new IllegalArgumentException("无法解析 Lambda 表达式对应的字段，请确保使用方法引用语法（如 User::getName）");
    }

    /**
     * 在类中查找方法。
     */
    private Method findMethod(Class<?> clazz, String methodName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new IllegalArgumentException("在类 " + clazz.getName() + " 中找不到方法：" + methodName);
    }

    /**
     * 驼峰转下划线。
     */
    private String camelToUnderline(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(ch));
        }
        return builder.toString();
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
