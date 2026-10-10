package com.wcdk.r2dbc.repository.metadata;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.dialect.DatabaseDialect;
import com.wcdk.r2dbc.dialect.PostgreSqlDatabaseDialect;
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
 *
 * @version 1.0
 **/
public final class RepositoryMetadata {

    private final Class<?> entityClass;

    private final String tableName;

    private final List<FieldColumn> columns;

    private final FieldColumn idColumn;

    private final FieldColumn logicDeleteColumn;

    private final WcdkR2dbcProperties properties;

    private final DatabaseDialect dialect;

    /*** 保留直接构造元数据时的ANSI引用行为。 @author wcdk ***/
    public RepositoryMetadata(Class<?> entityClass, WcdkR2dbcProperties properties) {
        this(entityClass, properties, PostgreSqlDatabaseDialect.INSTANCE);
    }

    /*** 依据仓储实际连接的方言引用标识符。 @author wcdk ***/
    public RepositoryMetadata(Class<?> entityClass, WcdkR2dbcProperties properties, DatabaseDialect dialect) {
        this.properties = properties;
        this.dialect = java.util.Objects.requireNonNull(dialect, "数据库方言不能为空");
        this.entityClass = entityClass;
        this.tableName = tableName(entityClass);
        this.columns = columns(entityClass);
        this.idColumn = columns.stream()
                .filter(FieldColumn::id)
                .findFirst()
                .orElseGet(() -> columns.stream()
                        .filter(column -> "id".equals(column.field().getName()))
                        .findFirst()
                        .orElse(null));
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

    public boolean hasIdColumn() {
        return idColumn != null;
    }

    public FieldColumn requireIdColumn() {
        if (idColumn == null) {
            throw new UnsupportedOperationException("实体未定义主键，不支持按主键操作：" + entityClass.getName());
        }
        return idColumn;
    }

    public FieldColumn logicDeleteColumn() {
        return logicDeleteColumn;
    }

    public FieldColumn columnByName(String columnName) {
        // 优先保留精确匹配，再以白名单匹配大小写变体；不接受 SQL 表达式或表别名。
        return columns.stream()
                .filter(column -> column.field().getName().equals(columnName)
                        || column.rawName().equals(columnName) || column.name().equals(columnName))
                .findFirst()
                .or(() -> columns.stream()
                        .filter(column -> column.field().getName().equalsIgnoreCase(columnName)
                                || column.rawName().equalsIgnoreCase(columnName)
                                || column.name().equalsIgnoreCase(columnName))
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException("实体字段不存在：" + columnName));
    }

    private String tableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        String name = table == null || table.value().isBlank() ? camelToUnderline(entityClass.getSimpleName()) : table.value();
        return identifier(name);
    }

    private List<FieldColumn> columns(Class<?> entityClass) {
        List<FieldColumn> result = new ArrayList<>();
        ReflectionUtils.doWithFields(entityClass, field -> {
            ReflectionUtils.makeAccessible(field);
            Column column = field.getAnnotation(Column.class);
            String name = column == null || column.value().isBlank() ? camelToUnderline(field.getName()) : column.value();
            result.add(new FieldColumn(field, identifier(name), field.isAnnotationPresent(Id.class)));
        }, field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers())
                && !field.isAnnotationPresent(Transient.class));
        return List.copyOf(result);
    }

    /*** 使用方言统一引用与转义表名、列名，关闭配置时保留原名。 @author wcdk ***/
    private String identifier(String name) {
        return properties.isQuoteIdentifier() ? dialect.quoteIdentifier(name) : name;
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
        /*** 返回驱动请求生成值时使用的原始映射列名。 @author wcdk ***/
        public String rawName() {
            Column column = field.getAnnotation(Column.class);
            return column == null || column.value().isBlank() ? camelToUnderline(field.getName()) : column.value();
        }
    }
}
