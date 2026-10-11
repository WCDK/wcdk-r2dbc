package com.wcdk.r2dbc.mapping;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.lang.reflect.Field;

/** 统一的默认命名规则，以及字段和表的显式注解处理规则。 */
public final class NamingStrategy {
    private NamingStrategy() {}

    public static String toSnakeCase(String value) {
        if (value == null || value.isEmpty()) return value;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isUpperCase(current) && i > 0) {
                char previous = value.charAt(i - 1);
                boolean wordBoundary = Character.isLowerCase(previous) || Character.isDigit(previous)
                        || (Character.isUpperCase(previous) && i + 1 < value.length()
                        && Character.isLowerCase(value.charAt(i + 1)));
                if (wordBoundary) result.append('_');
            }
            result.append(Character.toLowerCase(current));
        }
        return result.toString();
    }

    public static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        return column == null || column.value().isBlank() ? toSnakeCase(field.getName()) : column.value();
    }

    public static String tableName(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        return table == null || table.value().isBlank() ? toSnakeCase(type.getSimpleName()) : table.value();
    }

    public static String columnName(Class<?> type, String property) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try { return columnName(current.getDeclaredField(property)); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new IllegalArgumentException("实体字段不存在：" + property);
    }
}
