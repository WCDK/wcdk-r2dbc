package com.wcdk.r2dbc.core.executor;

import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.log.R2dbcSqlLogger;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.Map;

/**
 * R2DBC更新操作，负责执行SQL更新。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class R2dbcUpdateOperations {

    private final DatabaseClient databaseClient;

    private final ParameterBinder parameterBinder;

    private final SqlLifecycleExecutor lifecycleExecutor;

    private final R2dbcSqlLogger sqlLogger;

    public R2dbcUpdateOperations(DatabaseClient databaseClient,
                                 ParameterBinder parameterBinder,
                                 SqlLifecycleExecutor lifecycleExecutor,
                                 R2dbcSqlLogger sqlLogger) {
        this.databaseClient = databaseClient;
        this.parameterBinder = parameterBinder;
        this.lifecycleExecutor = lifecycleExecutor;
        this.sqlLogger = sqlLogger;
    }

    /**
     * 执行更新操作。
     *
     * @param sql SQL语句
     * @return 影响行数
     */
    public Mono<Long> update(String sql) {
        return update(sql, Map.of());
    }

    /**
     * 执行带参数的更新操作。
     *
     * @param sql        SQL语句
     * @param parameters 参数
     * @return 影响行数
     */
    public Mono<Long> update(String sql, Map<?, ?> parameters) {
        SqlLifecycleInterceptorChain chain = lifecycleExecutor.getChain();
        SqlExecutionContext context = lifecycleExecutor.createContext("update", (Map<String, Object>) parameters);
        context.setSql(sql);

        if (lifecycleExecutor.beforeCompile(chain, context)) {
            return Mono.empty();
        }

        if (lifecycleExecutor.afterCompile(chain, context)) {
            return Mono.empty();
        }

        if (lifecycleExecutor.beforeExecute(chain, context)) {
            return Mono.empty();
        }

        context.setStartTime(System.nanoTime());

        return Mono.deferContextual(contextView -> {
            sqlLogger.logSql(contextView, context.getSql(), context.getParameters());
            return execute(context.getSql(), context.getParameters()).fetch().rowsUpdated();
        }).doOnSuccess(r -> {
            context.setResult(r);
            context.setEndTime(System.nanoTime());
            lifecycleExecutor.afterExecute(chain, context);
        }).doOnError(e -> {
            context.setError(e);
            context.setEndTime(System.nanoTime());
            lifecycleExecutor.afterExecute(chain, context);
        });
    }

    /** Executes an already intercepted repository update without invoking the lifecycle chain again. */
    public Mono<Long> updateWithoutLifecycle(String sql, Map<?, ?> parameters) {
        return Mono.deferContextual(contextView -> {
            sqlLogger.logSql(contextView, sql, parameters);
            return execute(sql, parameters).fetch().rowsUpdated();
        });
    }

    /**
     * 批量执行SQL。
     *
     * @param sqlList SQL列表
     * @return 影响行数
     */
    public Mono<Long> batch(List<String> sqlList) {
        if (sqlList == null || sqlList.isEmpty()) {
            return Mono.just(0L);
        }
        return Flux.fromIterable(sqlList)
                .concatMap(this::update)
                .reduce(0L, Long::sum);
    }

    private DatabaseClient.GenericExecuteSpec execute(String sql, Map<?, ?> parameters) {
        return parameterBinder.bind(databaseClient, sql, parameters);
    }
}
