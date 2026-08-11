package com.wcdk.r2dbc.core.executor;

import io.r2dbc.spi.Type;

import java.util.Objects;

/**
 * 带类型的 SQL 参数。对于空值必须指定 Java 类型；还可以提供可选的
 * R2DBC 数据库类型，以满足需要原生类型的驱动要求。
 */
public record SqlParameter(Object value, Class<?> javaType, Type databaseType) {

    public SqlParameter {
        Objects.requireNonNull(javaType, "javaType");
        if (value != null && !javaType.isInstance(value) && !boxed(javaType).isInstance(value)) {
            throw new IllegalArgumentException("参数值类型 " + value.getClass().getName()
                    + " 无法赋值给 " + javaType.getName());
        }
    }

    public static SqlParameter of(Object value) {
        Objects.requireNonNull(value, "value");
        return new SqlParameter(value, value.getClass(), null);
    }

    public static SqlParameter nullOf(Class<?> javaType) {
        return new SqlParameter(null, javaType, null);
    }

    public static SqlParameter database(Object value, Class<?> javaType, Type databaseType) {
        return new SqlParameter(value, javaType, Objects.requireNonNull(databaseType, "databaseType"));
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
