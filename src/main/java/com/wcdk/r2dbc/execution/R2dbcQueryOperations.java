package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * R2DBC查询操作，负责执行SQL查询。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
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
                    () -> withExecutionLog(execute(context.getSql(), context.getParameters()).fetch().all(),
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
                    () -> withExecutionLog(execute(context.getSql(), context.getParameters()).map(mapper).all(),
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

    /** Executes an already intercepted repository query without invoking the lifecycle chain again. */
    public <T> Flux<T> queryWithoutLifecycle(String sql, Map<?, ?> parameters,
                                              BiFunction<Row, RowMetadata, T> mapper) {
        return Flux.deferContextual(contextView -> {
            return withExecutionLog(execute(sql, parameters).map(mapper).all(), sql, parameters);
        });
    }

    private <T> Flux<T> withExecutionLog(Flux<T> results, String sql, Map<?, ?> parameters) {
        AtomicLong resultCount = new AtomicLong();
        return results
                .doOnNext(ignored -> resultCount.incrementAndGet())
                .doFinally(signal -> sqlLogger.logExecution(sql, parameters,
                        signal == SignalType.ON_ERROR ? "ERROR" : resultCount.get()));
    }

    public <T> Mono<T> queryOneWithoutLifecycle(String sql, Map<?, ?> parameters,
                                                 BiFunction<Row, RowMetadata, T> mapper) {
        return queryWithoutLifecycle(sql, parameters, mapper).singleOrEmpty();
    }

    private DatabaseClient.GenericExecuteSpec execute(String sql, Map<?, ?> parameters) {
        return parameterBinder.bind(databaseClient, sql, parameters);
    }

    private Map<String, Object> copyParameters(Map<?, ?> parameters) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> copy.put(String.valueOf(key), value));
        }
        return copy;
    }
}
