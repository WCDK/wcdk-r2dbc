package com.wcdk.r2dbc.query;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 类型安全的删除条件构造器，通过方法引用指定字段。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 基本使用
 * LambdaDeleteWrapper<User> wrapper = new LambdaDeleteWrapper<>(User.class);
 * wrapper.eq(User::getStatus, 0)
 *        .lt(User::getCreateTime, LocalDateTime.now().minusDays(30));
 *
 * // 配合 Repository 使用
 * userRepository.delete(wrapper);
 * }</pre>
 *
 * @param <T> 实体类型
 * @author WCDK
 * @date 2026/8/6
 * @version 1.0
 **/
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
