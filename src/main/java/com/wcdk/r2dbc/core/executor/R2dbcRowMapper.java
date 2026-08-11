package com.wcdk.r2dbc.core.executor;

import io.r2dbc.spi.Row;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * R2DBC行映射器，负责将Row映射为实体对象。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class R2dbcRowMapper {

    private final ConcurrentMap<Class<?>, MappingPlan> mappingPlans = new ConcurrentHashMap<>();

    /**
     * 将Row映射为实体对象。
     *
     * @param row         数据库行
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 实体对象
     */
    public <T> T map(Row row, Class<T> entityClass) {
        try {
            MappingPlan plan = mappingPlans.computeIfAbsent(entityClass, this::createPlan);
            return entityClass.cast(plan.map(row, rowColumns(row)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (e instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Map entity failed: " + entityClass.getName(), e);
        }
    }

    private MappingPlan createPlan(Class<?> entityClass) {
        try {
            if (entityClass.isRecord()) {
                RecordComponent[] components = entityClass.getRecordComponents();
                Class<?>[] parameterTypes = java.util.Arrays.stream(components)
                        .map(RecordComponent::getType).toArray(Class<?>[]::new);
                Constructor<?> constructor = entityClass.getDeclaredConstructor(parameterTypes);
                constructor.setAccessible(true);
                List<ValueTarget> targets = new ArrayList<>(components.length);
                for (RecordComponent component : components) {
                    Field field = entityClass.getDeclaredField(component.getName());
                    targets.add(new ValueTarget(component.getName(), columnName(field), component.getType()));
                }
                return new ConstructorMappingPlan(constructor, List.copyOf(targets));
            }

            try {
                Constructor<?> constructor = entityClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                return new FieldMappingPlan(constructor, persistentFields(entityClass));
            } catch (NoSuchMethodException ignored) {
                Constructor<?>[] constructors = entityClass.getDeclaredConstructors();
                if (constructors.length != 1) {
                    throw new IllegalStateException("Entity requires a no-arg constructor or exactly one mapping constructor: "
                            + entityClass.getName());
                }
                Constructor<?> constructor = constructors[0];
                constructor.setAccessible(true);
                List<ValueTarget> targets = new ArrayList<>();
                for (Parameter parameter : constructor.getParameters()) {
                    Field field = findField(entityClass, parameter.getName());
                    targets.add(new ValueTarget(parameter.getName(),
                            field == null ? camelToUnderline(parameter.getName()) : columnName(field),
                            parameter.getType()));
                }
                return new ConstructorMappingPlan(constructor, List.copyOf(targets));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Create mapping plan failed: " + entityClass.getName(), e);
        }
    }

    private List<FieldTarget> persistentFields(Class<?> entityClass) {
        List<FieldTarget> targets = new ArrayList<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = entityClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        for (Class<?> type : hierarchy) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                field.setAccessible(true);
                targets.add(new FieldTarget(field, columnName(field)));
            }
        }
        return List.copyOf(targets);
    }

    private Field findField(Class<?> entityClass, String name) {
        for (Class<?> current = entityClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private Object mappedValue(Row row, Set<String> rowColumns, ValueTarget target) {
        String actualColumn = actualColumnName(rowColumns, target.column());
        if (actualColumn == null) {
            return primitiveDefault(target.type());
        }
        try {
            return convertValue(row.get(actualColumn), target.type());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot map column '" + actualColumn + "' to '"
                    + target.name() + "' (" + target.type().getName() + ")", e);
        }
    }

    private Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    /**
     * 类型转换。
     *
     * @param value      原始值
     * @param targetType 目标类型
     * @return 转换后的值
     */
    public Object convertValue(Object value, Class<?> targetType) {
        if (value == null || targetType == null || targetType == Object.class) {
            return value;
        }
        Class<?> boxedType = boxedType(targetType);
        if (boxedType.isInstance(value)) {
            return value;
        }
        if (Number.class.isAssignableFrom(boxedType) && value instanceof Number number) {
            return convertNumber(number, boxedType);
        }
        if (value instanceof Date date) {
            Object temporalValue = convertDate(date, boxedType);
            if (temporalValue != null) {
                return temporalValue;
            }
        }
        if (value instanceof LocalDateTime localDateTime) {
            Object temporalValue = convertLocalDateTime(localDateTime, boxedType);
            if (temporalValue != null) {
                return temporalValue;
            }
        }
        if (boxedType == String.class) {
            return String.valueOf(value);
        }
        if (boxedType == Boolean.class) {
            if (value instanceof Number number) {
                return number.longValue() != 0;
            }
            if (value instanceof CharSequence text) {
                String string = text.toString();
                return "1".equals(string) || Boolean.parseBoolean(string);
            }
        }
        if (boxedType.isEnum() && value instanceof CharSequence text) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumValue = Enum.valueOf((Class<? extends Enum>) boxedType.asSubclass(Enum.class), text.toString());
            return enumValue;
        }
        return value;
    }

    private Object convertDate(Date date, Class<?> targetType) {
        Instant instant = date.toInstant();
        if (targetType == Instant.class) {
            return instant;
        }
        if (targetType == LocalDateTime.class) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        }
        if (targetType == LocalDate.class) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate();
        }
        if (targetType == LocalTime.class) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalTime();
        }
        return null;
    }

    private Object convertLocalDateTime(LocalDateTime localDateTime, Class<?> targetType) {
        if (targetType == LocalDate.class) {
            return localDateTime.toLocalDate();
        }
        if (targetType == LocalTime.class) {
            return localDateTime.toLocalTime();
        }
        if (targetType == Instant.class) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (targetType == Date.class) {
            return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
        }
        return null;
    }

    private Object convertNumber(Number number, Class<?> targetType) {
        if (targetType == Long.class) {
            return number.longValue();
        }
        if (targetType == Integer.class) {
            return number.intValue();
        }
        if (targetType == Short.class) {
            return number.shortValue();
        }
        if (targetType == Byte.class) {
            return number.byteValue();
        }
        if (targetType == Double.class) {
            return number.doubleValue();
        }
        if (targetType == Float.class) {
            return number.floatValue();
        }
        if (targetType == BigDecimal.class) {
            return number instanceof BigDecimal decimal ? decimal : BigDecimal.valueOf(number.doubleValue());
        }
        if (targetType == BigInteger.class) {
            return number instanceof BigInteger integer ? integer : BigInteger.valueOf(number.longValue());
        }
        return number;
    }

    private Class<?> boxedType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.value().isBlank()) {
            return column.value();
        }
        return camelToUnderline(field.getName());
    }

    private String camelToUnderline(String value) {
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

    private interface MappingPlan {
        Object map(Row row, Set<String> rowColumns) throws ReflectiveOperationException;
    }

    private record ValueTarget(String name, String column, Class<?> type) {
    }

    private record FieldTarget(Field field, String column) {
    }

    private final class ConstructorMappingPlan implements MappingPlan {
        private final Constructor<?> constructor;
        private final List<ValueTarget> targets;

        private ConstructorMappingPlan(Constructor<?> constructor, List<ValueTarget> targets) {
            this.constructor = constructor;
            this.targets = targets;
        }

        @Override
        public Object map(Row row, Set<String> rowColumns) throws ReflectiveOperationException {
            Object[] arguments = new Object[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                arguments[i] = mappedValue(row, rowColumns, targets.get(i));
            }
            return constructor.newInstance(arguments);
        }
    }

    private final class FieldMappingPlan implements MappingPlan {
        private final Constructor<?> constructor;
        private final List<FieldTarget> targets;

        private FieldMappingPlan(Constructor<?> constructor, List<FieldTarget> targets) {
            this.constructor = constructor;
            this.targets = targets;
        }

        @Override
        public Object map(Row row, Set<String> rowColumns) throws ReflectiveOperationException {
            Object entity = constructor.newInstance();
            for (FieldTarget target : targets) {
                String actualColumn = actualColumnName(rowColumns, target.column());
                if (actualColumn == null) {
                    continue;
                }
                ValueTarget valueTarget = new ValueTarget(target.field().getName(),
                        target.column(), target.field().getType());
                target.field().set(entity, mappedValue(row, rowColumns, valueTarget));
            }
            return entity;
        }
    }

    private Set<String> rowColumns(Row row) {
        Set<String> columns = new HashSet<>();
        row.getMetadata().getColumnMetadatas().forEach(column -> columns.add(column.getName()));
        return columns;
    }

    private String actualColumnName(Set<String> rowColumns, String columnName) {
        if (rowColumns.contains(columnName)) {
            return columnName;
        }
        for (String rowColumn : rowColumns) {
            if (rowColumn.equalsIgnoreCase(columnName)) {
                return rowColumn;
            }
        }
        return null;
    }
}
