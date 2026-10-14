package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * R2DBC查询操作，负责执行SQL查询。
 *
 * @author WCDK
 *
 * @version 1.0
 */
public class R2dbcQueryOperations {

    private final DatabaseClient databaseClient;

    private final ParameterBinder parameterBinder;

    private final SqlLifecycleExecutor lifecycleExecutor;

    private final R2dbcSqlLogger sqlLogger;

    public R2dbcQueryOperations(DatabaseClient databaseClient,
                                ParameterBinder parameterBinder,
                                SqlLifecycleExecutor lifecycleExecutor,
                                R2dbcSqlLogger sqlLogger) {
        this.databaseClient = databaseClient;
        this.parameterBinder = parameterBinder;
        this.lifecycleExecutor = lifecycleExecutor;
        this.sqlLogger = sqlLogger;
    }

    /**
     * 执行SQL查询，返回Map列表。
     *
     * @param sql SQL语句
     * @return 查询结果
     */
    public Flux<Map<String, Object>> query(String sql) {
        return query(sql, Map.of());
    }

    /**
     * 执行带参数的SQL查询，返回Map列表。
     *
     * @param sql        SQL语句
     * @param parameters 参数
     * @return 查询结果
     */
    public Flux<Map<String, Object>> query(String sql, Map<?, ?> parameters) {
        return Flux.deferContextual(ignored -> {
            SqlLifecycleInterceptorChain chain = lifecycleExecutor.getChain();
            SqlExecutionContext context = lifecycleExecutor.createContext("query", copyParameters(parameters));
            context.setSql(sql);
            Mono<Boolean> preparation = lifecycleExecutor.prepare(chain, context, Mono::empty);
            return lifecycleExecutor.executeFlux(chain, context, preparation,
                    () -> withExecutionLog(reactor.util.context.Context.of(ignored).put(SqlLifecycleExecutor.EXECUTION_CONTEXT_KEY, context), executeMapped(context.getSql(), context.getParameters(), (row, metadata) -> {
                        Map<String, Object> values = new java.util.LinkedHashMap<>();
                        metadata.getColumnMetadatas().forEach(column -> values.put(column.getName(), row.get(column.getName())));
                        return values;
                    }, ignored),
                            context.getSql(), context.getParameters()));
        });
    }

    /**
     * 执行SQL查询，使用自定义映射器。
     *
     * @param sql    SQL语句
     * @param mapper 映射器
     * @param <T>    返回类型
     * @return 查询结果
     */
    public <T> Flux<T> query(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return query(sql, Map.of(), mapper);
    }

    /**
     * 执行带参数的SQL查询，使用自定义映射器。
     *
     * @param sql        SQL语句
     * @param parameters 参数
     * @param mapper     映射器
     * @param <T>        返回类型
     * @return 查询结果
     */
    public <T> Flux<T> query(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return Flux.deferContextual(ignored -> {
            SqlLifecycleInterceptorChain chain = lifecycleExecutor.getChain();
            SqlExecutionContext context = lifecycleExecutor.createContext("query", copyParameters(parameters));
            context.setSql(sql);
            Mono<Boolean> preparation = lifecycleExecutor.prepare(chain, context, Mono::empty);
            return lifecycleExecutor.executeFlux(chain, context, preparation,
                    () -> withExecutionLog(reactor.util.context.Context.of(ignored).put(SqlLifecycleExecutor.EXECUTION_CONTEXT_KEY, context), executeMapped(context.getSql(), context.getParameters(), mapper, ignored),
                            context.getSql(), context.getParameters()));
        });
    }

    /**
     * 查询单个Map结果。
     *
     * @param sql SQL语句
     * @return 查询结果
     */
    public Mono<Map<String, Object>> queryOne(String sql) {
        return query(sql).singleOrEmpty();
    }

    /**
     * 查询单个Map结果。
     *
     * @param sql        SQL语句
     * @param parameters 参数
     * @return 查询结果
     */
    public Mono<Map<String, Object>> queryOne(String sql, Map<?, ?> parameters) {
        return query(sql, parameters).singleOrEmpty();
    }

    /**
     * 查询单个结果，使用自定义映射器。
     *
     * @param sql    SQL语句
     * @param mapper 映射器
     * @param <T>    返回类型
     * @return 查询结果
     */
    public <T> Mono<T> queryOne(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return query(sql, mapper).singleOrEmpty();
    }

    /**
     * 查询单个结果，使用自定义映射器。
     *
     * @param sql        SQL语句
     * @param parameters 参数
     * @param mapper     映射器
     * @param <T>        返回类型
     * @return 查询结果
     */
    public <T> Mono<T> queryOne(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return query(sql, parameters, mapper).singleOrEmpty();
    }

    /** 执行已经经过拦截器处理的仓储查询，避免重复调用生命周期链。 */
    public <T> Flux<T> queryWithoutLifecycle(String sql, Map<?, ?> parameters,
                                              BiFunction<Row, RowMetadata, T> mapper) {
        return Flux.deferContextual(contextView -> {
            return withExecutionLog(contextView, executeMapped(sql, parameters, mapper, contextView), sql, parameters);
        });
    }

    /**
     * 隔离行映射异常，避免达梦R2DBC驱动将非SQLException错误强制转换为SQLException。
     *
     * @param sql        SQL语句
     * @param parameters 参数
     * @param mapper     行映射器
     * @param <T>        映射结果类型
     * @return 映射后的结果流
     */
    private <T> Flux<T> executeMapped(String sql, Map<?, ?> parameters,
                                      BiFunction<Row, RowMetadata, T> mapper, reactor.util.context.ContextView context) {
        return execute(sql, parameters, context)
                .filter((statement, next) -> Flux.from(next.execute(statement)).map(ResultColumnCache::new))
                .map((row, metadata) -> MappingResult.capture(row, metadata, mapper))
                .all()
                .handle((result, sink) -> {
                    if (result.error() != null) {
                        sink.error(result.error());
                    } else {
                        sink.next(result.value());
                    }
                });
    }
    private <T> Flux<T> withExecutionLog(reactor.util.context.ContextView context, Flux<T> results, String sql, Map<?, ?> parameters) {
        AtomicLong resultCount = new AtomicLong();
        return results
                .doOnNext(ignored -> resultCount.incrementAndGet())
                .doFinally(signal -> sqlLogger.logExecution(context, sql, parameters,
                        signal == SignalType.ON_ERROR ? "ERROR" : resultCount.get()));
    }

    public <T> Mono<T> queryOneWithoutLifecycle(String sql, Map<?, ?> parameters,
                                                 BiFunction<Row, RowMetadata, T> mapper) {
        return queryWithoutLifecycle(sql, parameters, mapper).singleOrEmpty();
    }

    /**
     * 适配达梦驱动将Instant直接强转为Date的问题。
     *
     * @param row 原始数据行
     * @return 兼容日期类型读取的数据行
     */
    static Row compatibleRow(Row row) {
        return new Row() {
            @Override
            public <T> T get(int index, Class<T> type) {
                if (isLegacyTemporalType(type)) {
                    return convertLegacyTemporal(row.get(index), type);
                }
                return row.get(index, type);
            }

            @Override
            public <T> T get(String name, Class<T> type) {
                if (isLegacyTemporalType(type)) {
                    return convertLegacyTemporal(row.get(name), type);
                }
                return row.get(name, type);
            }

            @Override
            public Object get(int index) {
                return normalizeLegacyValue(row.get(index));
            }

            @Override
            public Object get(String name) {
                return normalizeLegacyValue(row.get(name));
            }

            @Override
            public RowMetadata getMetadata() {
                return row.getMetadata();
            }
        };
    }

    private static boolean isLegacyTemporalType(Class<?> type) {
        return type == Date.class || type == Timestamp.class || type == java.sql.Date.class
                || type == Time.class || type == Instant.class || type == LocalDateTime.class
                || type == LocalDate.class || type == LocalTime.class;
    }

    private static <T> T convertLegacyTemporal(Object value, Class<T> type) {
        if (value instanceof Instant instant) {
            Object converted = switch (type.getName()) {
                case "java.util.Date" -> Date.from(instant);
                case "java.sql.Timestamp" -> Timestamp.from(instant);
                case "java.sql.Date" -> new java.sql.Date(instant.toEpochMilli());
                case "java.sql.Time" -> new Time(instant.toEpochMilli());
                case "java.time.Instant" -> instant;
                case "java.time.LocalDateTime" -> LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                case "java.time.LocalDate" -> LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate();
                case "java.time.LocalTime" -> LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalTime();
                default -> null;
            };
            if (converted != null) {
                return type.cast(converted);
            }
        }
        return value == null ? null : type.cast(value);
    }

    /**
     * 兼容旧代码通过无类型 Row.get 读取 Date 的场景。
     *
     * @param value 驱动返回值
     * @return 兼容后的值
     */
    private static Object normalizeLegacyValue(Object value) {
        return value instanceof Instant instant ? Date.from(instant) : value;
    }
    private DatabaseClient.GenericExecuteSpec execute(String sql, Map<?, ?> parameters,
                                                      reactor.util.context.ContextView context) {
        return parameterBinder.bind(databaseClient, sql, parameters, context);
    }

    private Map<Object, Object> copyParameters(Map<?, ?> parameters) {
        Map<Object, Object> copy = new java.util.LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> copy.put(key, value));
        }
        return copy;
    }
    /**
     * 将映射值或映射异常作为普通结果穿过存在缺陷的驱动映射链路。
     *
     * @param value 映射值
     * @param error 映射异常
     * @param <T>   映射结果类型
     */
    private record MappingResult<T>(T value, Throwable error) {

        private static <T> MappingResult<T> capture(Row row, RowMetadata metadata,
                                                    BiFunction<Row, RowMetadata, T> mapper) {
            try {
                return new MappingResult<>(mapper.apply(compatibleRow(row), metadata), null);
            } catch (Throwable error) {
                Exceptions.throwIfFatal(error);
                return new MappingResult<>(null, error);
            }
        }
    }
}
