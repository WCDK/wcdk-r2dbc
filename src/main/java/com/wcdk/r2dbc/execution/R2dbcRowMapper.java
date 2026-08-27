package com.wcdk.r2dbc.execution;

import io.r2dbc.spi.Row;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.PersistenceCreator;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * R2DBC行映射器，负责将Row映射为实体对象。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class R2dbcRowMapper {

    private final LongAdder mappingRequests = new LongAdder();
    private final LongAdder planMisses = new LongAdder();
    private final Map<Class<?>, Boolean> planOwners = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** ClassValue keeps plans scoped to this mapper without pinning reloadable entity ClassLoaders. */
    private final ClassValue<MappingPlan> mappingPlans = new ClassValue<>() {
        @Override
        protected MappingPlan computeValue(Class<?> type) {
            planMisses.increment();
            planOwners.put(type, Boolean.TRUE);
            return createPlan(type);
        }
    };
    private final List<R2dbcValueConverter> converters;

    public R2dbcRowMapper() {
        this(List.of());
    }

    public R2dbcRowMapper(List<R2dbcValueConverter> converters) {
        this.converters = converters == null ? List.of() : List.copyOf(converters);
    }

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
            mappingRequests.increment();
            MappingPlan plan = mappingPlans.get(entityClass);
            return entityClass.cast(plan.map(row, rowColumns(row)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (e instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("实体映射失败: " + entityClass.getName(), e);
        }
    }

    public CacheStats cacheStats() {
        long requests = mappingRequests.sum();
        long misses = planMisses.sum();
        synchronized (planOwners) {
            return new CacheStats(Math.max(0, requests - misses), misses, planOwners.size());
        }
    }

    public record CacheStats(long hits, long misses, int size) {
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
                return new ConstructorMappingPlan(constructor, List.copyOf(targets), List.of());
            }

            Constructor<?>[] constructors = entityClass.getDeclaredConstructors();
            List<Constructor<?>> selected = java.util.Arrays.stream(constructors)
                    .filter(candidate -> candidate.isAnnotationPresent(PersistenceCreator.class))
                    .toList();
            if (selected.size() > 1) {
                throw new IllegalStateException("实体有多个@PersistenceCreator构造函数: "
                        + entityClass.getName());
            }
            if (!selected.isEmpty()) {
                return constructorPlan(entityClass, selected.getFirst());
            }

            try {
                Constructor<?> constructor = entityClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                return new FieldMappingPlan(constructor, fieldTargets(entityClass));
            } catch (NoSuchMethodException ignored) {
                if (constructors.length != 1) {
                    throw new IllegalStateException("实体需要无参构造函数或恰好一个映射构造函数: "
                            + entityClass.getName());
                }
                return constructorPlan(entityClass, constructors[0]);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("创建映射计划失败: " + entityClass.getName(), e);
        }
    }

    private ConstructorMappingPlan constructorPlan(Class<?> entityClass, Constructor<?> constructor) {
        constructor.setAccessible(true);
        List<ValueTarget> targets = new ArrayList<>();
        for (Parameter parameter : constructor.getParameters()) {
            Field field = findField(entityClass, parameter.getName());
            targets.add(new ValueTarget(parameter.getName(),
                    field == null ? camelToUnderline(parameter.getName()) : columnName(field),
                    parameter.getType()));
        }
        return new ConstructorMappingPlan(constructor, List.copyOf(targets), transientFields(entityClass));
    }

    private List<FieldTarget> fieldTargets(Class<?> entityClass) {
        Map<String, FieldTarget> targets = new LinkedHashMap<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = entityClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        for (Class<?> type : hierarchy) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                targets.put(field.getName(), new FieldTarget(field, columnName(field),
                        !field.isAnnotationPresent(Transient.class)));
            }
        }
        return List.copyOf(targets.values());
    }

    private List<FieldTarget> transientFields(Class<?> entityClass) {
        Map<String, FieldTarget> targets = new LinkedHashMap<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = entityClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        for (Class<?> type : hierarchy) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                field.setAccessible(true);
                targets.put(field.getName(), new FieldTarget(field, columnName(field), false));
            }
        }
        return List.copyOf(targets.values());
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
            if (target.type().isPrimitive()) {
                throw new IllegalStateException("必需的基本类型属性 '" + target.name()
                        + "' 缺少列 '" + target.column() + "'");
            }
            return null;
        }
        try {
            Object value = row.get(actualColumn);
            if (value == null && target.type().isPrimitive()) {
                throw new IllegalStateException("SQL NULL无法赋值给基本类型属性 '"
                        + target.name() + "'，列 '" + actualColumn + "'");
            }
            return convertValue(value, target.type());
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法将列 '" + actualColumn + "' 映射到 '"
                    + target.name() + "' (" + target.type().getName() + ")", e);
        }
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
        for (R2dbcValueConverter converter : converters) {
            if (converter.supports(value.getClass(), boxedType)) {
                Object converted = converter.convert(value, boxedType);
                if (converted != null && !boxedType.isInstance(converted)) {
                    throw new IllegalStateException("转换器 " + converter.getClass().getName()
                            + " 对 " + boxedType.getName() + " 返回了 " + converted.getClass().getName());
                }
                return converted;
            }
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
        if (value instanceof Instant instant) {
            if (boxedType == Date.class) {
                return Date.from(instant);
            }
            if (boxedType == LocalDateTime.class) {
                return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            }
            if (boxedType == LocalDate.class) {
                return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate();
            }
            if (boxedType == LocalTime.class) {
                return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalTime();
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

    private record FieldTarget(Field field, String column, boolean required) {
    }

    private final class ConstructorMappingPlan implements MappingPlan {
        private final Constructor<?> constructor;
        private final List<ValueTarget> targets;
        private final List<FieldTarget> transientFields;

        private ConstructorMappingPlan(Constructor<?> constructor, List<ValueTarget> targets,
                                       List<FieldTarget> transientFields) {
            this.constructor = constructor;
            this.targets = targets;
            this.transientFields = transientFields;
        }

        @Override
        public Object map(Row row, Set<String> rowColumns) throws ReflectiveOperationException {
            Object[] arguments = new Object[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                arguments[i] = mappedValue(row, rowColumns, targets.get(i));
            }
            Object entity = constructor.newInstance(arguments);
            for (FieldTarget target : transientFields) {
                if (actualColumnName(rowColumns, target.column()) == null) {
                    continue;
                }
                ValueTarget valueTarget = new ValueTarget(target.field().getName(),
                        target.column(), target.field().getType());
                target.field().set(entity, mappedValue(row, rowColumns, valueTarget));
            }
            return entity;
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
                    if (target.required() && target.field().getType().isPrimitive()) {
                        throw new IllegalStateException("必需的基本类型属性 '" + target.field().getName()
                                + "' 缺少列 '" + target.column() + "'");
                    }
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
