package com.wcdk.r2dbc.core.query;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 类型安全的查询条件构造器，通过方法引用指定字段。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 基本使用
 * LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>(User.class);
 * wrapper.eq(User::getStatus, 1)
 *        .like(User::getName, "张")
 *        .orderByDesc(User::getCreateTime);
 *
 * // 配合 Repository 使用
 * userRepository.selectList(wrapper);
 * }</pre>
 *
 * @param <T> 实体类型
 * @author WCDK
 * @date 2026/8/6
 * @version 1.0
 **/
public class LambdaQueryWrapper<T> {

    private final Class<T> entityClass;

    private final List<Condition> conditions = new ArrayList<>();

    private final List<OrderBy> orderByList = new ArrayList<>();

    private Integer limit;

    private Integer offset;

    public LambdaQueryWrapper(Class<T> entityClass) {
        if (entityClass == null) {
            throw new IllegalArgumentException("实体类不能为空");
        }
        this.entityClass = entityClass;
    }

    /**
     * 创建 LambdaQueryWrapper 实例。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return LambdaQueryWrapper 实例
     */
    public static <T> LambdaQueryWrapper<T> of(Class<T> entityClass) {
        return new LambdaQueryWrapper<>(entityClass);
    }

    /**
     * 创建 LambdaQueryWrapper 实例（通过实体实例推断类型）。
     *
     * @param entity 实体实例
     * @param <T>    实体类型
     * @return LambdaQueryWrapper 实例
     */
    public static <T> LambdaQueryWrapper<T> of(T entity) {
        if (entity == null) {
            throw new IllegalArgumentException("实体实例不能为空");
        }
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        return new LambdaQueryWrapper<>(entityClass);
    }

    // ==================== 条件方法 ====================

    /**
     * 等值查询：column = value
     */
    public <V> LambdaQueryWrapper<T> eq(SFunction<T, ?> field, V value) {
        return condition(field, "=", value);
    }

    /**
     * 不等查询：column &lt;&gt; value
     */
    public <V> LambdaQueryWrapper<T> ne(SFunction<T, ?> field, V value) {
        return condition(field, "<>", value);
    }

    /**
     * 大于查询：column &gt; value
     */
    public <V> LambdaQueryWrapper<T> gt(SFunction<T, ?> field, V value) {
        return condition(field, ">", value);
    }

    /**
     * 大于等于查询：column &gt;= value
     */
    public <V> LambdaQueryWrapper<T> ge(SFunction<T, ?> field, V value) {
        return condition(field, ">=", value);
    }

    /**
     * 小于查询：column &lt; value
     */
    public <V> LambdaQueryWrapper<T> lt(SFunction<T, ?> field, V value) {
        return condition(field, "<", value);
    }

    /**
     * 小于等于查询：column &lt;= value
     */
    public <V> LambdaQueryWrapper<T> le(SFunction<T, ?> field, V value) {
        return condition(field, "<=", value);
    }

    /**
     * 模糊查询：column LIKE value
     */
    public <V> LambdaQueryWrapper<T> like(SFunction<T, ?> field, V value) {
        return condition(field, "LIKE", value);
    }

    /**
     * IN 查询：column IN (values)
     */
    @SafeVarargs
    public final <V> LambdaQueryWrapper<T> in(SFunction<T, ?> field, V... values) {
        return condition(field, "IN", values);
    }

    /**
     * BETWEEN 查询：column BETWEEN start AND end
     */
    public <V> LambdaQueryWrapper<T> between(SFunction<T, ?> field, V start, V end) {
        String column = resolveColumn(field);
        conditions.add(new Condition(column, "BETWEEN", new Object[]{start, end}));
        return this;
    }

    /**
     * IS NULL 查询：column IS NULL
     */
    public LambdaQueryWrapper<T> isNull(SFunction<T, ?> field) {
        return condition(field, "IS NULL", null);
    }

    /**
     * IS NOT NULL 查询：column IS NOT NULL
     */
    public LambdaQueryWrapper<T> isNotNull(SFunction<T, ?> field) {
        return condition(field, "IS NOT NULL", null);
    }

    // ==================== 排序方法 ====================

    /**
     * 升序排序
     */
    public LambdaQueryWrapper<T> orderByAsc(SFunction<T, ?> field) {
        return orderBy(field, true);
    }

    /**
     * 降序排序
     */
    public LambdaQueryWrapper<T> orderByDesc(SFunction<T, ?> field) {
        return orderBy(field, false);
    }

    // ==================== 分页方法 ====================

    /**
     * 设置查询条数
     */
    public LambdaQueryWrapper<T> limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("查询条数必须大于 0");
        }
        this.limit = limit;
        return this;
    }

    /**
     * 设置偏移量
     */
    public LambdaQueryWrapper<T> offset(Integer offset) {
        if (offset == null || offset < 0) {
            throw new IllegalArgumentException("查询偏移量不能小于 0");
        }
        this.offset = offset;
        return this;
    }

    /**
     * 设置分页参数
     *
     * @param pageNo   页码（从1开始）
     * @param pageSize 每页条数
     */
    public LambdaQueryWrapper<T> page(int pageNo, int pageSize) {
        if (pageNo <= 0) {
            throw new IllegalArgumentException("页码必须大于 0");
        }
        limit(pageSize);
        return offset((pageNo - 1) * pageSize);
    }

    // ==================== 获取结果 ====================

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

    public Class<T> entityClass() {
        return entityClass;
    }

    // ==================== 内部方法 ====================

    private <V> LambdaQueryWrapper<T> condition(SFunction<T, ?> field, String operator, V value) {
        String column = resolveColumn(field);
        conditions.add(new Condition(column, operator, value));
        return this;
    }

    private LambdaQueryWrapper<T> orderBy(SFunction<T, ?> field, boolean asc) {
        String column = resolveColumn(field);
        orderByList.add(new OrderBy(column, asc));
        return this;
    }

    /**
     * 解析 Lambda 表达式对应的数据库列名。
     *
     * @param field Lambda 表达式
     * @return 数据库列名
     */
    private String resolveColumn(SFunction<T, ?> field) {
        String fieldName = resolveFieldName(field);
        return camelToUnderline(fieldName);
    }

    /**
     * 解析 Lambda 表达式对应的字段名。
     *
     * @param field Lambda 表达式
     * @return Java 字段名
     */
    private String resolveFieldName(SFunction<T, ?> field) {
        Method writeMethod = getWriteMethod(field);
        String methodName = writeMethod.getName();

        // 从 getter/setter 方法名提取字段名
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
            // 尝试通过序列化 Lambda 获取方法
            if (field instanceof Serializable serializable) {
                Method writeMethod = serializable.getClass().getDeclaredMethod("writeReplace");
                writeMethod.setAccessible(true);
                SerializedLambda lambda = (SerializedLambda) writeMethod.invoke(serializable);
                String implMethodName = lambda.getImplMethodName();
                return findMethod(entityClass, implMethodName);
            }
        } catch (Exception ignored) {
            // 降级处理
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
     *
     * @param <T> 输入类型
     * @param <R> 返回类型
     */
    @FunctionalInterface
    public interface SFunction<T, R> extends Function<T, R>, Serializable {
    }

    /**
     * 条件定义。
     */
    public record Condition(String column, String operator, Object value) {
    }

    /**
     * 排序定义。
     */
    public record OrderBy(String column, boolean asc) {
    }
}
