package com.wcdk.r2dbc;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.reactivestreams.Publisher;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * @version 1.0
 * @auther WCDK
 * @date 2026/7/20
 **/
public class R2dbcUtil {

    private static final Logger log = LoggerFactory.getLogger(R2dbcUtil.class);

    private final DatabaseClient databaseClient;

    private final R2dbcEntityTemplate entityTemplate;

    private final TransactionalOperator transactionalOperator;

    private final WcdkR2dbcProperties properties;

    private final WcdkSpringR2dbcProperties springR2dbcProperties;


    public R2dbcUtil(DatabaseClient databaseClient,
                     R2dbcEntityTemplate entityTemplate,
                     TransactionalOperator transactionalOperator) {
        this(databaseClient, entityTemplate, transactionalOperator, new WcdkR2dbcProperties(), new WcdkSpringR2dbcProperties());
    }

    public R2dbcUtil(DatabaseClient databaseClient,
                     R2dbcEntityTemplate entityTemplate,
                     TransactionalOperator transactionalOperator,
                     WcdkR2dbcProperties properties) {
        this(databaseClient, entityTemplate, transactionalOperator, properties, new WcdkSpringR2dbcProperties());
    }

    public R2dbcUtil(DatabaseClient databaseClient,
                     R2dbcEntityTemplate entityTemplate,
                     TransactionalOperator transactionalOperator,
                     WcdkR2dbcProperties properties,
                     WcdkSpringR2dbcProperties springR2dbcProperties) {
        this.databaseClient = databaseClient;
        this.entityTemplate = entityTemplate;
        this.transactionalOperator = transactionalOperator;
        this.properties = properties == null ? new WcdkR2dbcProperties() : properties;
        this.springR2dbcProperties = springR2dbcProperties == null ? new WcdkSpringR2dbcProperties() : springR2dbcProperties;
    }

    public DatabaseClient databaseClient() {
        return databaseClient;
    }

    public R2dbcEntityTemplate entityTemplate() {
        if (entityTemplate == null) {
            throw new IllegalStateException("R2DBC entity template is missing");
        }
        return entityTemplate;
    }

    public Flux<Map<String, Object>> query(String sql) {
        return query(sql, Map.of());
    }

    public Flux<Map<String, Object>> query(String sql, Map<?, ?> parameters) {
        String requiredSql = requireSql(sql);
        return Flux.deferContextual(contextView -> {
            logSql(contextView, requiredSql, parameters);
            return execute(requiredSql, parameters).fetch().all();
        });
    }

    public <T> Flux<T> query(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return query(sql, Map.of(), mapper);
    }

    public <T> Flux<T> query(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        String requiredSql = requireSql(sql);
        return Flux.deferContextual(contextView -> {
            logSql(contextView, requiredSql, parameters);
            return execute(requiredSql, parameters).map(mapper).all();
        });
    }

    public Mono<Map<String, Object>> queryOne(String sql) {
        return query(sql).next();
    }

    public Mono<Map<String, Object>> queryOne(String sql, Map<?, ?> parameters) {
        return query(sql, parameters).next();
    }

    public <T> Mono<T> queryOne(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return query(sql, mapper).next();
    }

    public <T> Mono<T> queryOne(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return query(sql, parameters, mapper).next();
    }

    public Mono<Long> update(String sql) {
        return update(sql, Map.of());
    }

    public Mono<Long> update(String sql, Map<?, ?> parameters) {
        String requiredSql = requireSql(sql);
        return Mono.deferContextual(contextView -> {
            logSql(contextView, requiredSql, parameters);
            return execute(requiredSql, parameters).fetch().rowsUpdated();
        });
    }

    public Mono<Long> batch(List<String> sqlList) {
        if (sqlList == null || sqlList.isEmpty()) {
            return Mono.just(0L);
        }
        return Flux.fromIterable(sqlList)
                .concatMap(this::update)
                .reduce(0L, Long::sum);
    }

    public <T> Flux<T> transaction(Function<DatabaseClient, Publisher<T>> action) {
        if (transactionalOperator == null) {
            throw new IllegalStateException("R2DBC transactional operator is missing");
        }
        return transactionalOperator.transactional(Flux.from(action.apply(databaseClient)));
    }

    public <T> Flux<T> transaction(String dataSource, Function<DatabaseClient, Publisher<T>> action) {
        return dataSource(dataSource, transaction(action));
    }

    public <T> Mono<T> dataSource(String dataSource, Mono<T> publisher) {
        return R2dbcDataSourceContext.use(dataSource, publisher);
    }

    public <T> Flux<T> dataSource(String dataSource, Flux<T> publisher) {
        return R2dbcDataSourceContext.use(dataSource, publisher);
    }

    public <T> Mono<T> insert(T entity) {
        return entityTemplate().insert(entity);
    }

    public <T> Mono<T> save(T entity) {
        return entityTemplate().update(entity);
    }

    public <T> Mono<T> delete(T entity) {
        return entityTemplate().delete(entity);
    }

    public <T> Flux<T> select(Query query, Class<T> entityClass) {
        return entityTemplate().select(query, entityClass);
    }

    public <T> Mono<T> selectOne(Query query, Class<T> entityClass) {
        return entityTemplate().selectOne(query, entityClass);
    }

    public Mono<Long> count(Query query, Class<?> entityClass) {
        return entityTemplate().count(query, entityClass);
    }

    public <T> T map(Row row, Class<T> entityClass) {
        try {
            T entity = entityClass.getDeclaredConstructor().newInstance();
            Set<String> rowColumns = rowColumns(row);
            for (Field field : entityClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String columnName = columnName(field);
                String actualColumnName = actualColumnName(rowColumns, columnName);
                if (field.getAnnotation(Transient.class) != null && actualColumnName == null) {
                    continue;
                }
                field.setAccessible(true);
                Object value = convertValue(row.get(actualColumnName == null ? columnName : actualColumnName), field.getType());
                field.set(entity, value);
            }
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Map entity failed: " + entityClass.getName(), e);
        }
    }

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

    private DatabaseClient.GenericExecuteSpec execute(String sql, Map<?, ?> parameters) {
        String requiredSql = requireSql(sql);
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(requiredSql);
        if (parameters == null || parameters.isEmpty()) {
            return spec;
        }
        for (Map.Entry<?, ?> entry : parameters.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key instanceof Number numberKey) {
                spec = bind(spec, numberKey.intValue(), value);
                continue;
            }
            spec = bind(spec, String.valueOf(key), value);
        }
        return spec;
    }

    private DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, int index, Object value) {
        if (value == null) {
            return spec.bindNull(index, Object.class);
        }
        return spec.bind(index, value);
    }

    private DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, String identifier, Object value) {
        if (value == null) {
            return spec.bindNull(identifier, Object.class);
        }
        return spec.bind(identifier, value);
    }

    private void logSql(ContextView contextView, String sql, Map<?, ?> parameters) {
        if (!properties.isSqlLogEnabled()) {
            return;
        }
        String dataSource = currentDataSource(contextView);
        if (parameters == null || parameters.isEmpty()) {
            log.info("=================R2DBC==========START==========");
            log.info("数据源：{}", dataSource);
            log.info("执行SQL：{}",normalizeSql(sql));
            log.info("=================R2DBC==========END==========");
            return;
        }
        log.info("=================R2DBC==========START==========");
        log.info("数据源：{}", dataSource);
        log.info("执行SQL：{}",normalizeSql(sql));
        log.info("参数：{}",parameters);
        log.info("=================R2DBC==========END==========");
    }

    private String currentDataSource(ContextView contextView) {
        String dataSource = R2dbcDataSourceContext.get(contextView);
        if (dataSource != null && !dataSource.isBlank()) {
            return dataSource;
        }
        String primary = springR2dbcProperties.getPrimary();
        return primary == null || primary.isBlank() ? "master" : primary;
    }

    private String normalizeSql(String sql) {
        return sql.strip().replaceAll("\\s+", " ");
    }

    private String requireSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("R2DBC SQL is blank");
        }
        return sql;
    }

    private String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.value().isBlank()) {
            return column.value();
        }
        String value = field.getName();
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



