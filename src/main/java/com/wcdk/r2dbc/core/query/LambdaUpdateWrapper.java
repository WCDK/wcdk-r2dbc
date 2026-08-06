package com.wcdk.r2dbc.core.query;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 类型安全的更新条件构造器，通过方法引用指定字段。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 基本使用
 * LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>(User.class);
 * wrapper.set(User::getName, "张三")
 *        .set(User::getStatus, 1)
 *        .eq(User::getId, 1L);
 *
 * // 配合 Repository 使用
 * userRepository.update(wrapper);
 * }</pre>
 *
 * @param <T> 实体类型
 * @author WCDK
 * @date 2026/8/6
 * @version 1.0
 **/
public class LambdaUpdateWrapper<T> {

    private static final Map<Class<?>, Map<String, String>> FIELD_CACHE = new ConcurrentHashMap<>();

    private final Class<T> entityClass;

    private final Map<String, Object> setValues = new LinkedHashMap<>();

    private final List<LambdaCondition> conditions = new ArrayList<>();

    public LambdaUpdateWrapper(Class<T> entityClass) {
        if (entityClass == null) {
            throw new IllegalArgumentException("实体类不能为空");
        }
        this.entityClass = entityClass;
    }

    /**
     * 创建 LambdaUpdateWrapper 实例。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return LambdaUpdateWrapper 实例
     */
    public static <T> LambdaUpdateWrapper<T> of(Class<T> entityClass) {
        return new LambdaUpdateWrapper<>(entityClass);
    }

    /**
     * 创建 LambdaUpdateWrapper 实例（通过实体实例推断类型）。
     *
     * @param entity 实体实例
     * @param <T>    实体类型
     * @return LambdaUpdateWrapper 实例
     */
    public static <T> LambdaUpdateWrapper<T> of(T entity) {
        if (entity == null) {
            throw new IllegalArgumentException("实体实例不能为空");
        }
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        return new LambdaUpdateWrapper<>(entityClass);
    }

    // ==================== SET 方法 ====================

    /**
     * 设置字段值
     *
     * @param field 字段引用
     * @param value 字段值
     */
    public <V> LambdaUpdateWrapper<T> set(SFunction<T, ?> field, V value) {
        String column = resolveColumn(field);
        setValues.put(column, value);
        return this;
    }

    /**
     * 设置字段值为 NULL
     *
     * @param field 字段引用
     */
    public LambdaUpdateWrapper<T> setNull(SFunction<T, ?> field) {
        String column = resolveColumn(field);
        setValues.put(column, null);
        return this;
    }

    /**
     * 设置字段值递增
     *
     * @param field  字段引用
     * @param increment 递增值
     */
    public <V extends Number> LambdaUpdateWrapper<T> setIncrement(SFunction<T, ?> field, V increment) {
        String column = resolveColumn(field);
        setValues.put(column, new IncrementValue(increment, true));
        return this;
    }

    /**
     * 设置字段值递减
     *
     * @param field  字段引用
     * @param decrement 递减值
     */
    public <V extends Number> LambdaUpdateWrapper<T> setDecrement(SFunction<T, ?> field, V decrement) {
        String column = resolveColumn(field);
        setValues.put(column, new IncrementValue(decrement, false));
        return this;
    }

    // ==================== WHERE 条件方法 ====================

    /**
     * 等值条件：column = value
     */
    public <V> LambdaUpdateWrapper<T> eq(SFunction<T, ?> field, V value) {
        return condition(field, "=", value);
    }

    /**
     * 不等条件：column &lt;&gt; value
     */
    public <V> LambdaUpdateWrapper<T> ne(SFunction<T, ?> field, V value) {
        return condition(field, "<>", value);
    }

    /**
     * 大于条件：column &gt; value
     */
    public <V> LambdaUpdateWrapper<T> gt(SFunction<T, ?> field, V value) {
        return condition(field, ">", value);
    }

    /**
     * 大于等于条件：column &gt;= value
     */
    public <V> LambdaUpdateWrapper<T> ge(SFunction<T, ?> field, V value) {
        return condition(field, ">=", value);
    }

    /**
     * 小于条件：column &lt; value
     */
    public <V> LambdaUpdateWrapper<T> lt(SFunction<T, ?> field, V value) {
        return condition(field, "<", value);
    }

    /**
     * 小于等于条件：column &lt;= value
     */
    public <V> LambdaUpdateWrapper<T> le(SFunction<T, ?> field, V value) {
        return condition(field, "<=", value);
    }

    /**
     * IN 条件：column IN (values)
     */
    @SafeVarargs
    public final <V> LambdaUpdateWrapper<T> in(SFunction<T, ?> field, V... values) {
        return condition(field, "IN", values);
    }

    /**
     * BETWEEN 条件：column BETWEEN start AND end
     */
    public <V> LambdaUpdateWrapper<T> between(SFunction<T, ?> field, V start, V end) {
        String column = resolveColumn(field);
        conditions.add(new LambdaCondition(column, "BETWEEN", new Object[]{start, end}));
        return this;
    }

    /**
     * IS NULL 条件：column IS NULL
     */
    public LambdaUpdateWrapper<T> isNull(SFunction<T, ?> field) {
        return condition(field, "IS NULL", null);
    }

    /**
     * IS NOT NULL 条件：column IS NOT NULL
     */
    public LambdaUpdateWrapper<T> isNotNull(SFunction<T, ?> field) {
        return condition(field, "IS NOT NULL", null);
    }

    // ==================== 获取结果 ====================

    /**
     * 获取 SET 子句的字段和值
     */
    public Map<String, Object> getSetValues() {
        return Collections.unmodifiableMap(setValues);
    }

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
     * 是否有 SET 子句
     */
    public boolean hasSetValues() {
        return !setValues.isEmpty();
    }

    /**
     * 是否有 WHERE 条件
     */
    public boolean hasConditions() {
        return !conditions.isEmpty();
    }

    // ==================== 内部方法 ====================

    private <V> LambdaUpdateWrapper<T> condition(SFunction<T, ?> field, String operator, V value) {
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

    /**
     * 递增/递减值。
     */
    public record IncrementValue(Object value, boolean increment) {
    }
}
