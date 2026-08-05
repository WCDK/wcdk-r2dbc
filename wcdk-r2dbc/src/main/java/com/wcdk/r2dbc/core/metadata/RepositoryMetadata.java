package com.wcdk.r2dbc.core.metadata;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 仓储实体元数据。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public final class RepositoryMetadata {

    private final Class<?> entityClass;

    private final String tableName;

    private final List<FieldColumn> columns;

    private final FieldColumn idColumn;

    private final FieldColumn logicDeleteColumn;

    public RepositoryMetadata(Class<?> entityClass, WcdkR2dbcProperties properties) {
        this.entityClass = entityClass;
        this.tableName = tableName(entityClass, properties);
        this.columns = columns(entityClass, properties);
        this.idColumn = columns.stream()
                .filter(FieldColumn::id)
                .findFirst()
                .orElseGet(() -> columns.stream()
                        .filter(column -> "id".equals(column.field().getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("实体缺少主键字段：" + entityClass.getName())));
        this.logicDeleteColumn = columns.stream()
                .filter(column -> column.field().getName().equals(properties.getLogicDeleteField()))
                .findFirst()
                .orElse(null);
    }

    public Class<?> entityClass() {
        return entityClass;
    }

    public String tableName() {
        return tableName;
    }

    public List<FieldColumn> columns() {
        return columns;
    }

    public FieldColumn idColumn() {
        return idColumn;
    }

    public FieldColumn logicDeleteColumn() {
        return logicDeleteColumn;
    }

    public FieldColumn columnByName(String columnName) {
        return columns.stream()
                .filter(column -> column.field().getName().equals(columnName) || column.name().equals(columnName) || column.name().equals("\"" + columnName + "\""))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("实体字段不存在：" + columnName));
    }

    private static String tableName(Class<?> entityClass, WcdkR2dbcProperties properties) {
        Table table = entityClass.getAnnotation(Table.class);
        String name = table == null || table.value().isBlank() ? camelToUnderline(entityClass.getSimpleName()) : table.value();
        return identifier(name, properties);
    }

    private static List<FieldColumn> columns(Class<?> entityClass, WcdkR2dbcProperties properties) {
        List<FieldColumn> result = new ArrayList<>();
        ReflectionUtils.doWithFields(entityClass, field -> {
            ReflectionUtils.makeAccessible(field);
            Column column = field.getAnnotation(Column.class);
            String name = column == null || column.value().isBlank() ? camelToUnderline(field.getName()) : column.value();
            result.add(new FieldColumn(field, identifier(name, properties), field.isAnnotationPresent(Id.class)));
        }, field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers())
                && !field.isAnnotationPresent(Transient.class));
        return List.copyOf(result);
    }

    private static String identifier(String name, WcdkR2dbcProperties properties) {
        return properties.isQuoteIdentifier() ? "\"" + name + "\"" : name;
    }

    private static String camelToUnderline(String value) {
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

    public record FieldColumn(Field field, String name, boolean id) {
    }
}
