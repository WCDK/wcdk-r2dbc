package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import com.wcdk.r2dbc.transaction.ManualTransaction;
import com.wcdk.r2dbc.transaction.TransactionManager;
import com.wcdk.r2dbc.transaction.TransactionTemplate;
import io.r2dbc.spi.Connection;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * R2DBC事务操作，负责事务管理。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class R2dbcTransactionOperations {

    private final DatabaseClient databaseClient;

    private final TransactionalOperator transactionalOperator;

    private final TransactionManager transactionManager;

    private final TransactionTemplate transactionTemplate;

    public R2dbcTransactionOperations(DatabaseClient databaseClient, TransactionalOperator transactionalOperator) {
        this(databaseClient, transactionalOperator, null);
    }

    public R2dbcTransactionOperations(DatabaseClient databaseClient, TransactionalOperator transactionalOperator,
                                      TransactionManager transactionManager) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
        this.transactionManager = transactionManager;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    /**
     * 使用TransactionalOperator执行事务操作。
     *
     * @param action 事务操作
     * @param <T>    返回类型
     * @return 操作结果
     */
    public <T> Flux<T> transaction(Function<DatabaseClient, Publisher<T>> action) {
        if (transactionalOperator == null) {
            throw new IllegalStateException("R2DBC事务操作符缺失");
        }
        if (action == null) {
            throw new IllegalArgumentException("事务操作不能为空");
        }
        Flux<T> execution = Flux.defer(() -> Flux.from(action.apply(databaseClient)))
                .as(transactionalOperator::transactional);
        return R2dbcDataSourceContext.pinTransactionDataSource(execution);
    }

    /**
     * 创建手动事务。
     *
     * @return 手动事务
     */
    public Mono<ManualTransaction> createManualTransaction() {
        if (transactionManager == null) {
            throw new IllegalStateException("TransactionManager未配置");
        }
        return transactionManager.createTransaction();
    }

    /**
     * 创建手动事务。
     *
     * @param transactionName 事务名称
     * @return 手动事务
     */
    public Mono<ManualTransaction> createManualTransaction(String transactionName) {
        if (transactionManager == null) {
            throw new IllegalStateException("TransactionManager未配置");
        }
        return transactionManager.createTransaction(transactionName);
    }

    /**
     * 使用事务模板执行操作（自动提交/回滚）。
     *
     * @param action 事务操作
     * @param <T>    返回类型
     * @return 操作结果
     */
    public <T> Mono<T> executeInTransaction(Function<Connection, Publisher<T>> action) {
        if (transactionTemplate == null) {
            throw new IllegalStateException("TransactionTemplate未配置");
        }
        return transactionTemplate.execute(action);
    }

    /**
     * 使用事务模板执行操作（自动提交/回滚）。
     *
     * @param transactionName 事务名称
     * @param action          事务操作
     * @param <T>             返回类型
     * @return 操作结果
     */
    public <T> Mono<T> executeInTransaction(String transactionName, Function<Connection, Publisher<T>> action) {
        if (transactionTemplate == null) {
            throw new IllegalStateException("TransactionTemplate未配置");
        }
        return transactionTemplate.execute(transactionName, action);
    }

    /**
     * 使用只读事务模板执行操作。
     *
     * @param action 事务操作
     * @param <T>    返回类型
     * @return 操作结果
     */

    /**
     * 获取事务模板。
     *
     * @return 事务模板
     */
    public TransactionTemplate getTransactionTemplate() {
        return transactionTemplate;
    }

    /**
     * 获取事务管理器。
     *
     * @return 事务管理器
     */
    public TransactionManager getTransactionManager() {
        return transactionManager;
    }
}
