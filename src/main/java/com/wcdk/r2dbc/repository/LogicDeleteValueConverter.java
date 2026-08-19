package com.wcdk.r2dbc.repository;

/**
 * 逻辑删除参数类型转换工具。
 *
 * @author WCDK
 * @version 1.0
 */
final class LogicDeleteValueConverter {

    private LogicDeleteValueConverter() {
    }

    static Object convert(Object value, Class<?> targetType) {
        if (value == null || targetType == null) {
            return value;
        }
        Class<?> boxedType = box(targetType);
        if (boxedType.isInstance(value)) {
            return value;
        }
        if (boxedType == String.class) {
            return String.valueOf(value);
        }
        if (value instanceof String text) {
            if (boxedType == Boolean.class) {
                return Boolean.valueOf(text);
            }
            if (boxedType == Byte.class) {
                return Byte.valueOf(text);
            }
            if (boxedType == Short.class) {
                return Short.valueOf(text);
            }
            if (boxedType == Integer.class) {
                return Integer.valueOf(text);
            }
            if (boxedType == Long.class) {
                return Long.valueOf(text);
            }
            if (boxedType == Float.class) {
                return Float.valueOf(text);
            }
            if (boxedType == Double.class) {
                return Double.valueOf(text);
            }
        }
        if (value instanceof Number number) {
            if (boxedType == Byte.class) {
                return number.byteValue();
            }
            if (boxedType == Short.class) {
                return number.shortValue();
            }
            if (boxedType == Integer.class) {
                return number.intValue();
            }
            if (boxedType == Long.class) {
                return number.longValue();
            }
            if (boxedType == Float.class) {
                return number.floatValue();
            }
            if (boxedType == Double.class) {
                return number.doubleValue();
            }
        }
        return value;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
}