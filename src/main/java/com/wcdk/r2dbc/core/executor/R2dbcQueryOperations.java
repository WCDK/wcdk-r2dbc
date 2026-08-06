package com.wcdk.r2dbc.core.executor;

import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.log.R2dbcSqlLogger;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.Map;
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
        SqlLifecycleInterceptorChain chain = lifecycleExecutor.getChain();
        SqlExecutionContext context = lifecycleExecutor.createContext("query", (Map<String, Object>) parameters);
        context.setSql(sql);

        return lifecycleExecutor.beforeCompileReactive(chain, context)
                .filter(terminated -> !terminated)
                .then(lifecycleExecutor.afterCompileReactive(chain, context))
                .filter(terminated -> !terminated)
                .then(lifecycleExecutor.beforeExecuteReactive(chain, context))
                .filter(terminated -> !terminated)
                .thenMany(Flux.deferContextual(contextView -> {
                    context.setStartTime(System.nanoTime());
                    sqlLogger.logSql(contextView, context.getSql(), context.getParameters());
                    return execute(context.getSql(), context.getParameters()).fetch().all();
                }))
                .doOnComplete(() -> {
                    context.setEndTime(System.nanoTime());
                    lifecycleExecutor.afterExecuteReactive(chain, context).subscribe();
                })
                .doOnError(e -> {
                    context.setError(e);
                    context.setEndTime(System.nanoTime());
                    lifecycleExecutor.afterExecuteReactive(chain, context).subscribe();
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
        SqlLifecycleInterceptorChain chain = lifecycleExecutor.getChain();
        SqlExecutionContext context = lifecycleExecutor.createContext("query", (Map<String, Object>) parameters);
        context.setSql(sql);

        return lifecycleExecutor.beforeCompileReactive(chain, context)
                .filter(terminated -> !terminated)
                .then(lifecycleExecutor.afterCompileReactive(chain, context))
                .filter(terminated -> !terminated)
                .then(lifecycleExecutor.beforeExecuteReactive(chain, context))
                .filter(terminated -> !terminated)
                .thenMany(Flux.deferContextual(contextView -> {
                    context.setStartTime(System.nanoTime());
                    sqlLogger.logSql(contextView, context.getSql(), context.getParameters());
                    return execute(context.getSql(), context.getParameters()).map(mapper).all();
                }))
                .doOnComplete(() -> {
                    context.setEndTime(System.nanoTime());
                    lifecycleExecutor.afterExecuteReactive(chain, context).subscribe();
                })
                .doOnError(e -> {
                    context.setError(e);
                    context.setEndTime(System.nanoTime());
                    lifecycleExecutor.afterExecuteReactive(chain, context).subscribe();
                });
    }

    /**
     * 查询单个Map结果。
     *
     * @param sql SQL语句
     * @return 查询结果
     */
    public Mono<Map<String, Object>> queryOne(String sql) {
        return query(sql).next();
    }

    /**
     * 查询单个Map结果。
     *
     * @param sql        SQL语句
     * @param parameters 参数
     * @return 查询结果
     */
    public Mono<Map<String, Object>> queryOne(String sql, Map<?, ?> parameters) {
        return query(sql, parameters).next();
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
        return query(sql, mapper).next();
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
        return query(sql, parameters, mapper).next();
    }

    /** Executes an already intercepted repository query without invoking the lifecycle chain again. */
    public <T> Flux<T> queryWithoutLifecycle(String sql, Map<?, ?> parameters,
                                              BiFunction<Row, RowMetadata, T> mapper) {
        return Flux.deferContextual(contextView -> {
            sqlLogger.logSql(contextView, sql, parameters);
            return execute(sql, parameters).map(mapper).all();
        });
    }

    public <T> Mono<T> queryOneWithoutLifecycle(String sql, Map<?, ?> parameters,
                                                 BiFunction<Row, RowMetadata, T> mapper) {
        return queryWithoutLifecycle(sql, parameters, mapper).next();
    }

    private DatabaseClient.GenericExecuteSpec execute(String sql, Map<?, ?> parameters) {
        return parameterBinder.bind(databaseClient, sql, parameters);
    }
}
