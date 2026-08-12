package com.wcdk.r2dbc;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceRouter;
import com.wcdk.r2dbc.execution.ParameterBinder;
import com.wcdk.r2dbc.execution.R2dbcQueryOperations;
import com.wcdk.r2dbc.execution.R2dbcRowMapper;
import com.wcdk.r2dbc.execution.R2dbcTransactionOperations;
import com.wcdk.r2dbc.execution.R2dbcUpdateOperations;
import com.wcdk.r2dbc.execution.SqlLifecycleExecutor;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import com.wcdk.r2dbc.transaction.ManualTransaction;
import com.wcdk.r2dbc.transaction.TransactionManager;
import com.wcdk.r2dbc.transaction.TransactionTemplate;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.reactivestreams.Publisher;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * R2DBC工具类（门面模式），提供统一的API入口。
 *
 * @version 1.0
 * @auther WCDK
 * @date 2026/7/20
 **/
public class R2dbcUtil {

    private final DatabaseClient databaseClient;

    private final R2dbcEntityTemplate entityTemplate;

    private final WcdkR2dbcProperties properties;

    private final WcdkSpringR2dbcProperties springR2dbcProperties;

    // 基础组件
    private final ParameterBinder parameterBinder;
    private final SqlLifecycleExecutor lifecycleExecutor;
    private final R2dbcRowMapper rowMapper;
    private final R2dbcSqlLogger sqlLogger;
    private final R2dbcDataSourceRouter dataSourceRouter;

    // 操作组件
    private final R2dbcQueryOperations queryOperations;
    private final R2dbcUpdateOperations updateOperations;
    private final R2dbcTransactionOperations transactionOperations;

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
        this(databaseClient, entityTemplate, transactionalOperator, properties, springR2dbcProperties,
                null, new SqlLifecycleInterceptorChain(List.of(), List.of()));
    }

    public R2dbcUtil(DatabaseClient databaseClient,
                     R2dbcEntityTemplate entityTemplate,
                     TransactionalOperator transactionalOperator,
                     WcdkR2dbcProperties properties,
                     WcdkSpringR2dbcProperties springR2dbcProperties,
                     TransactionManager transactionManager) {
        this(databaseClient, entityTemplate, transactionalOperator, properties, springR2dbcProperties,
                transactionManager, new SqlLifecycleInterceptorChain(List.of(), List.of()));
    }

    public R2dbcUtil(DatabaseClient databaseClient,
                     R2dbcEntityTemplate entityTemplate,
                     TransactionalOperator transactionalOperator,
                     WcdkR2dbcProperties properties,
                     WcdkSpringR2dbcProperties springR2dbcProperties,
                     TransactionManager transactionManager,
                     SqlLifecycleInterceptorChain interceptorChain) {
        this.databaseClient = databaseClient;
        this.entityTemplate = entityTemplate;
        this.properties = properties == null ? new WcdkR2dbcProperties() : properties;
        this.springR2dbcProperties = springR2dbcProperties == null ? new WcdkSpringR2dbcProperties() : springR2dbcProperties;

        // 初始化基础组件
        this.parameterBinder = new ParameterBinder();
        this.lifecycleExecutor = new SqlLifecycleExecutor(interceptorChain);
        this.rowMapper = new R2dbcRowMapper();
        this.sqlLogger = new R2dbcSqlLogger(this.properties, this.springR2dbcProperties);
        this.dataSourceRouter = new R2dbcDataSourceRouter();

        // 初始化操作组件
        this.queryOperations = new R2dbcQueryOperations(databaseClient, parameterBinder, lifecycleExecutor, sqlLogger);
        this.updateOperations = new R2dbcUpdateOperations(databaseClient, parameterBinder, lifecycleExecutor, sqlLogger);
        this.transactionOperations = new R2dbcTransactionOperations(databaseClient, transactionalOperator, transactionManager);
    }

    public R2dbcUtil(DatabaseClient databaseClient,
                     R2dbcEntityTemplate entityTemplate,
                     TransactionalOperator transactionalOperator,
                     WcdkR2dbcProperties properties,
                     WcdkSpringR2dbcProperties springR2dbcProperties,
                     TransactionManager transactionManager,
                     ParameterBinder parameterBinder,
                     SqlLifecycleExecutor lifecycleExecutor,
                     R2dbcRowMapper rowMapper,
                     R2dbcSqlLogger sqlLogger,
                     R2dbcDataSourceRouter dataSourceRouter) {
        this.databaseClient = java.util.Objects.requireNonNull(databaseClient, "databaseClient");
        this.entityTemplate = entityTemplate;
        this.properties = properties == null ? new WcdkR2dbcProperties() : properties;
        this.springR2dbcProperties = springR2dbcProperties == null
                ? new WcdkSpringR2dbcProperties() : springR2dbcProperties;
        this.parameterBinder = java.util.Objects.requireNonNull(parameterBinder, "parameterBinder");
        this.lifecycleExecutor = java.util.Objects.requireNonNull(lifecycleExecutor, "lifecycleExecutor");
        this.rowMapper = java.util.Objects.requireNonNull(rowMapper, "rowMapper");
        this.sqlLogger = java.util.Objects.requireNonNull(sqlLogger, "sqlLogger");
        this.dataSourceRouter = java.util.Objects.requireNonNull(dataSourceRouter, "dataSourceRouter");
        this.queryOperations = new R2dbcQueryOperations(databaseClient, parameterBinder, lifecycleExecutor, sqlLogger);
        this.updateOperations = new R2dbcUpdateOperations(databaseClient, parameterBinder, lifecycleExecutor, sqlLogger);
        this.transactionOperations = new R2dbcTransactionOperations(
                databaseClient, transactionalOperator, transactionManager);
    }

    // ==================== 委托方法：查询执行 ====================

    SqlLifecycleExecutor getLifecycleExecutorInternal() {
        return lifecycleExecutor;
    }

    DatabaseClient databaseClient() {
        return databaseClient;
    }

    R2dbcEntityTemplate entityTemplate() {
        if (entityTemplate == null) {
            throw new IllegalStateException("R2DBC实体模板缺失");
        }
        return entityTemplate;
    }

    public Flux<Map<String, Object>> query(String sql) {
        return queryOperations.query(sql);
    }

    public Flux<Map<String, Object>> query(String sql, Map<?, ?> parameters) {
        return queryOperations.query(sql, parameters);
    }

    public <T> Flux<T> query(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return queryOperations.query(sql, mapper);
    }

    public <T> Flux<T> query(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return queryOperations.query(sql, parameters, mapper);
    }

    public Mono<Map<String, Object>> queryOne(String sql) {
        return queryOperations.queryOne(sql);
    }

    public Mono<Map<String, Object>> queryOne(String sql, Map<?, ?> parameters) {
        return queryOperations.queryOne(sql, parameters);
    }

    public <T> Mono<T> queryOne(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return queryOperations.queryOne(sql, mapper);
    }

    public <T> Mono<T> queryOne(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return queryOperations.queryOne(sql, parameters, mapper);
    }

    <T> Flux<T> queryWithoutLifecycle(String sql, Map<?, ?> parameters,
                                              BiFunction<Row, RowMetadata, T> mapper) {
        return queryOperations.queryWithoutLifecycle(sql, parameters, mapper);
    }

    <T> Mono<T> queryOneWithoutLifecycle(String sql, Map<?, ?> parameters,
                                                 BiFunction<Row, RowMetadata, T> mapper) {
        return queryOperations.queryOneWithoutLifecycle(sql, parameters, mapper);
    }

    public Mono<Long> update(String sql) {
        return updateOperations.update(sql);
    }

    public Mono<Long> update(String sql, Map<?, ?> parameters) {
        return updateOperations.update(sql, parameters);
    }

    Mono<Long> updateWithoutLifecycle(String sql, Map<?, ?> parameters) {
        return updateOperations.updateWithoutLifecycle(sql, parameters);
    }

    public Mono<Long> batch(List<String> sqlList) {
        return updateOperations.batch(sqlList);
    }

    // ==================== 委托方法：事务管理 ====================

    public <T> Flux<T> transaction(Function<DatabaseClient, Publisher<T>> action) {
        return transactionOperations.transaction(action);
    }

    public <T> Flux<T> transaction(String dataSource, Function<DatabaseClient, Publisher<T>> action) {
        return dataSourceRouter.dataSource(dataSource, transactionOperations.transaction(action));
    }

    public Mono<ManualTransaction> createManualTransaction() {
        return transactionOperations.createManualTransaction();
    }

    public Mono<ManualTransaction> createManualTransaction(String transactionName) {
        return transactionOperations.createManualTransaction(transactionName);
    }

    public <T> Mono<T> executeInTransaction(Function<Connection, Publisher<T>> action) {
        return transactionOperations.executeInTransaction(action);
    }

    public <T> Mono<T> executeInTransaction(String transactionName, Function<Connection, Publisher<T>> action) {
        return transactionOperations.executeInTransaction(transactionName, action);
    }

    public <T> Mono<T> executeInReadOnlyTransaction(Function<Connection, Publisher<T>> action) {
        return transactionOperations.executeInReadOnlyTransaction(action);
    }

    public TransactionTemplate getTransactionTemplate() {
        return transactionOperations.getTransactionTemplate();
    }

    public TransactionManager getTransactionManager() {
        return transactionOperations.getTransactionManager();
    }

    // ==================== 委托方法：数据源切换 ====================

    public <T> Mono<T> dataSource(String dataSource, Mono<T> publisher) {
        return dataSourceRouter.dataSource(dataSource, publisher);
    }

    public <T> Flux<T> dataSource(String dataSource, Flux<T> publisher) {
        return dataSourceRouter.dataSource(dataSource, publisher);
    }

    // ==================== 委托方法：实体操作 ====================

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

    // ==================== 委托方法：实体映射 ====================

    <T> T map(Row row, Class<T> entityClass) {
        return rowMapper.map(row, entityClass);
    }

    Object convertValue(Object value, Class<?> targetType) {
        return rowMapper.convertValue(value, targetType);
    }

}
