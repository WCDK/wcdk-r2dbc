package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import com.wcdk.r2dbc.dialect.DatabaseDialect;
import com.wcdk.r2dbc.dialect.DatabaseDialects;
import com.wcdk.r2dbc.dialect.GeneratedKeyStrategy;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * R2DBC更新操作，负责执行SQL更新。
 *
 * @author WCDK
 *
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
        return Mono.deferContextual(ignored -> {
            SqlLifecycleInterceptorChain chain = lifecycleExecutor.getChain();
            Map<String, Object> parameterCopy = new java.util.LinkedHashMap<>();
            if (parameters != null) {
                parameters.forEach((key, value) -> parameterCopy.put(String.valueOf(key), value));
            }
            SqlExecutionContext context = lifecycleExecutor.createContext("update", parameterCopy);
            context.setSql(sql);
            Mono<Boolean> preparation = lifecycleExecutor.prepare(chain, context, Mono::empty);
            return lifecycleExecutor.executeMono(chain, context, preparation,
                    () -> execute(context.getSql(), context.getParameters(), ignored).fetch().rowsUpdated()
                            .doOnSuccess(count -> sqlLogger.logExecution(context.getSql(), context.getParameters(),
                                    count == null ? 0 : count))
                            .doOnError(error -> sqlLogger.logExecution(
                                    context.getSql(), context.getParameters(), error)));
        });
    }

    /** Executes an already intercepted repository update without invoking the lifecycle chain again. */
    public Mono<Long> updateWithoutLifecycle(String sql, Map<?, ?> parameters) {
        return Mono.deferContextual(contextView -> {
            return execute(sql, parameters, contextView).fetch().rowsUpdated()
                    .doOnSuccess(count -> sqlLogger.logExecution(sql, parameters, count == null ? 0 : count))
            .doOnError(error -> sqlLogger.logExecution(sql, parameters, error));
        });
    }

    /***
     * 请求并读取数据库生成的主键，复用仓储外层生命周期。
     * @author wcdk
     **/
    public Mono<Object> insertReturningIdWithoutLifecycle(String sql, Map<?, ?> parameters, String idColumn) {
        return insertReturningIdWithoutLifecycle(sql, parameters, idColumn, null);
    }

    /** Uses the same identifier quoting as the INSERT, including Oracle driver RETURNING INTO. */
    public Mono<Object> insertReturningIdWithoutLifecycle(String sql, Map<?, ?> parameters,
                                                          String idColumn, String renderedIdColumn) {
        return Mono.deferContextual(context -> {
            var factory = databaseClient.getConnectionFactory();
            if (factory instanceof DynamicRoutingConnectionFactory routing) {
                factory = routing.getConnectionFactory(R2dbcDataSourceContext.get(context));
            }
            DatabaseDialect dialect = DatabaseDialects.get(factory);
            String renderedColumn = renderedIdColumn == null
                    ? dialect.quoteIdentifier(idColumn) : renderedIdColumn;
            GeneratedKeyStrategy strategy = dialect.generatedKeyStrategy();
            if (strategy == GeneratedKeyStrategy.NONE) {
                return Mono.error(new UnsupportedOperationException("方言不支持数据库生成主键：" + dialect.databaseType()));
            }
            // PostgreSQL 拼接 SQL RETURNING；Oracle/达梦由驱动请求生成值。
            String insertSql = strategy == GeneratedKeyStrategy.RETURNING
                    ? sql + dialect.renderGeneratedKey(renderedColumn) : sql;
            var spec = execute(insertSql, parameters, context);
            if (strategy == GeneratedKeyStrategy.LAST_INSERT_ID || !dialect.supportsReturning()) {
                // MySQL 从本条 INSERT 的响应读取 last insert id，避免额外连接上的 SELECT。
                spec = spec.filter(statement -> statement.returnGeneratedValues(dialect.generatedValueColumn(idColumn, renderedColumn)));
            }
            return spec
                .map((row, metadata) -> java.util.Objects.requireNonNull(row.get(0), "数据库返回的主键为空"))
                .all()
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(new IllegalStateException("数据库未返回生成的主键：" + idColumn)))
                .doOnSuccess(id -> sqlLogger.logExecution(insertSql, parameters, 1L))
                .doOnError(error -> sqlLogger.logExecution(insertSql, parameters, error));
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

    private DatabaseClient.GenericExecuteSpec execute(String sql, Map<?, ?> parameters,
                                                      reactor.util.context.ContextView context) {
        return parameterBinder.bind(databaseClient, sql, parameters, context);
    }
}
