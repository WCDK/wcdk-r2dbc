package com.wcdk.r2dbc;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.core.datasource.R2dbcDataSourceRouter;
import com.wcdk.r2dbc.core.executor.R2dbcQueryExecutor;
import com.wcdk.r2dbc.core.log.R2dbcSqlLogger;
import com.wcdk.r2dbc.core.mapper.R2dbcEntityMapper;
import com.wcdk.r2dbc.core.transaction.ManualTransaction;
import com.wcdk.r2dbc.core.transaction.R2dbcTransactionHelper;
import com.wcdk.r2dbc.core.transaction.TransactionManager;
import com.wcdk.r2dbc.core.transaction.TransactionTemplate;
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

    private final R2dbcQueryExecutor queryExecutor;

    private final R2dbcTransactionHelper transactionHelper;

    private final R2dbcEntityMapper entityMapper;

    private final R2dbcDataSourceRouter dataSourceRouter;

    private final R2dbcSqlLogger sqlLogger;

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
        this.properties = properties == null ? new WcdkR2dbcProperties() : properties;
        this.springR2dbcProperties = springR2dbcProperties == null ? new WcdkSpringR2dbcProperties() : springR2dbcProperties;
        this.sqlLogger = new R2dbcSqlLogger(this.properties, this.springR2dbcProperties);
        this.queryExecutor = new R2dbcQueryExecutor(databaseClient, sqlLogger);
        this.transactionHelper = new R2dbcTransactionHelper(databaseClient, transactionalOperator);
        this.entityMapper = new R2dbcEntityMapper();
        this.dataSourceRouter = new R2dbcDataSourceRouter();
    }

    public R2dbcUtil(DatabaseClient databaseClient,
                     R2dbcEntityTemplate entityTemplate,
                     TransactionalOperator transactionalOperator,
                     WcdkR2dbcProperties properties,
                     WcdkSpringR2dbcProperties springR2dbcProperties,
                     TransactionManager transactionManager) {
        this.databaseClient = databaseClient;
        this.entityTemplate = entityTemplate;
        this.properties = properties == null ? new WcdkR2dbcProperties() : properties;
        this.springR2dbcProperties = springR2dbcProperties == null ? new WcdkSpringR2dbcProperties() : springR2dbcProperties;
        this.sqlLogger = new R2dbcSqlLogger(this.properties, this.springR2dbcProperties);
        this.queryExecutor = new R2dbcQueryExecutor(databaseClient, sqlLogger);
        this.transactionHelper = new R2dbcTransactionHelper(databaseClient, transactionalOperator, transactionManager);
        this.entityMapper = new R2dbcEntityMapper();
        this.dataSourceRouter = new R2dbcDataSourceRouter();
    }

    private final WcdkR2dbcProperties properties;

    private final WcdkSpringR2dbcProperties springR2dbcProperties;

    // ==================== 委托方法：查询执行 ====================

    public DatabaseClient databaseClient() {
        return queryExecutor.databaseClient();
    }

    public R2dbcEntityTemplate entityTemplate() {
        if (entityTemplate == null) {
            throw new IllegalStateException("R2DBC entity template is missing");
        }
        return entityTemplate;
    }

    public Flux<Map<String, Object>> query(String sql) {
        return queryExecutor.query(sql);
    }

    public Flux<Map<String, Object>> query(String sql, Map<?, ?> parameters) {
        return queryExecutor.query(sql, parameters);
    }

    public <T> Flux<T> query(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return queryExecutor.query(sql, mapper);
    }

    public <T> Flux<T> query(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return queryExecutor.query(sql, parameters, mapper);
    }

    public Mono<Map<String, Object>> queryOne(String sql) {
        return queryExecutor.queryOne(sql);
    }

    public Mono<Map<String, Object>> queryOne(String sql, Map<?, ?> parameters) {
        return queryExecutor.queryOne(sql, parameters);
    }

    public <T> Mono<T> queryOne(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return queryExecutor.queryOne(sql, mapper);
    }

    public <T> Mono<T> queryOne(String sql, Map<?, ?> parameters, BiFunction<Row, RowMetadata, T> mapper) {
        return queryExecutor.queryOne(sql, parameters, mapper);
    }

    public Mono<Long> update(String sql) {
        return queryExecutor.update(sql);
    }

    public Mono<Long> update(String sql, Map<?, ?> parameters) {
        return queryExecutor.update(sql, parameters);
    }

    public Mono<Long> batch(List<String> sqlList) {
        return queryExecutor.batch(sqlList);
    }

    // ==================== 委托方法：事务管理 ====================

    public <T> Flux<T> transaction(Function<DatabaseClient, Publisher<T>> action) {
        return transactionHelper.transaction(action);
    }

    public <T> Flux<T> transaction(String dataSource, Function<DatabaseClient, Publisher<T>> action) {
        return dataSourceRouter.dataSource(dataSource, transactionHelper.transaction(action));
    }

    public Mono<ManualTransaction> createManualTransaction() {
        return transactionHelper.createManualTransaction();
    }

    public Mono<ManualTransaction> createManualTransaction(String transactionName) {
        return transactionHelper.createManualTransaction(transactionName);
    }

    public <T> Mono<T> executeInTransaction(Function<Connection, Publisher<T>> action) {
        return transactionHelper.executeInTransaction(action);
    }

    public <T> Mono<T> executeInTransaction(String transactionName, Function<Connection, Publisher<T>> action) {
        return transactionHelper.executeInTransaction(transactionName, action);
    }

    public <T> Mono<T> executeInReadOnlyTransaction(Function<Connection, Publisher<T>> action) {
        return transactionHelper.executeInReadOnlyTransaction(action);
    }

    public TransactionTemplate getTransactionTemplate() {
        return transactionHelper.getTransactionTemplate();
    }

    public TransactionManager getTransactionManager() {
        return transactionHelper.getTransactionManager();
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

    public <T> T map(Row row, Class<T> entityClass) {
        return entityMapper.map(row, entityClass);
    }

    public Object convertValue(Object value, Class<?> targetType) {
        return entityMapper.convertValue(value, targetType);
    }

    // ==================== 获取内部组件 ====================

    public R2dbcQueryExecutor getQueryExecutor() {
        return queryExecutor;
    }

    public R2dbcTransactionHelper getTransactionHelper() {
        return transactionHelper;
    }

    public R2dbcEntityMapper getEntityMapper() {
        return entityMapper;
    }

    public R2dbcDataSourceRouter getDataSourceRouter() {
        return dataSourceRouter;
    }

    public R2dbcSqlLogger getSqlLogger() {
        return sqlLogger;
    }
}
