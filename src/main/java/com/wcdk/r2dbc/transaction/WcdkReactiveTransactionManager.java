package com.wcdk.r2dbc.transaction;

import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

/**
 * WCDK 响应式事务管理器，负责把 Spring 事务与动态数据源锁定机制连接起来。
 * @author wcdk
 */
public final class WcdkReactiveTransactionManager implements ReactiveTransactionManager {

    private final ReactiveTransactionManager delegate;
    private final String primaryDataSource;

    /**
     * 创建基于指定 R2DBC 连接工厂的事务管理器。
     * @author wcdk
     * @param connectionFactory R2DBC 连接工厂
     */
    public WcdkReactiveTransactionManager(ConnectionFactory connectionFactory) {
        this(new R2dbcTransactionManager(connectionFactory), primaryDataSource(connectionFactory));
    }

    /**
     * 创建包装已有事务管理器的数据源锁定事务管理器。
     * @author wcdk
     * @param delegate 被包装的事务管理器
     */
    public WcdkReactiveTransactionManager(ReactiveTransactionManager delegate) {
        this(delegate, "<primary>");
    }

    /**
     * 创建带主数据源名称的数据源锁定事务管理器。
     * @author wcdk
     * @param delegate 被包装的事务管理器
     * @param primaryDataSource 主数据源名称
     */
    public WcdkReactiveTransactionManager(ReactiveTransactionManager delegate, String primaryDataSource) {
        this.delegate = delegate;
        this.primaryDataSource = primaryDataSource;
    }

    /**
     * 开启或加入 Spring 响应式事务，并登记 canonical 数据源。
     * @author wcdk
     * @param definition 事务定义
     * @return 响应式事务状态
     */
    @Override
    public Mono<ReactiveTransaction> getReactiveTransaction(TransactionDefinition definition) {
        return Mono.deferContextual(context -> {
            String currentDataSource = R2dbcDataSourceContext.get(context);
            String dataSource = "<primary>".equals(primaryDataSource) ? null
                    : currentDataSource == null ? primaryDataSource : currentDataSource;
            int propagation = definition == null ? TransactionDefinition.PROPAGATION_REQUIRED
                    : definition.getPropagationBehavior();
            boolean suspends = propagation == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                    || propagation == TransactionDefinition.PROPAGATION_NOT_SUPPORTED;
            return (suspends || dataSource == null ? Mono.<Void>empty() : verifyExistingDataSource(dataSource))
                    .then(delegate.getReactiveTransaction(definition))
                    .flatMap(transaction -> bindDataSource(dataSource).thenReturn(transaction));
        });
    }

    /**
     * 提交事务。
     * @author wcdk
     * @param transaction 事务状态
     * @return 完成信号
     */
    @Override
    public Mono<Void> commit(ReactiveTransaction transaction) {
        return delegate.commit(transaction);
    }

    /**
     * 回滚事务。
     * @author wcdk
     * @param transaction 事务状态
     * @return 完成信号
     */
    @Override
    public Mono<Void> rollback(ReactiveTransaction transaction) {
        return delegate.rollback(transaction);
    }

    /**
     * 根据连接工厂解析主数据源名称。
     * @author wcdk
     */
    private static String primaryDataSource(ConnectionFactory connectionFactory) {
        return connectionFactory instanceof DynamicRoutingConnectionFactory routing
                ? routing.getPrimary() : "<primary>";
    }

    /**
     * 校验已存在事务的数据源与当前请求一致。
     * @author wcdk
     * @param requested 当前请求数据源
     * @return 校验结果
     */
    private Mono<Void> verifyExistingDataSource(String requested) {
        return R2dbcDataSourceContext.currentManager()
                .flatMap(manager -> {
                    R2dbcDataSourceContext.verifySpringDataSource(manager, requested);
                    return Mono.<Void>empty();
                })
                .onErrorResume(NoTransactionException.class, ignored -> Mono.empty());
    }

    /**
     * 在当前 Spring 事务上下文登记 canonical 数据源。
     * @author wcdk
     * @param dataSource canonical 数据源
     * @return 登记结果
     */
    private Mono<Void> bindDataSource(String dataSource) {
        return R2dbcDataSourceContext.currentManager()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "委托事务管理器未建立响应式事务上下文，无法锁定数据源；请使用 R2dbcTransactionManager "
                                + "并通过 TransactionalOperator 或 Spring 响应式 @Transactional 调用",
                        new NoTransactionException("未找到响应式事务上下文"))))
                .doOnNext(manager -> R2dbcDataSourceContext.bindSpringDataSource(manager, dataSource))
                .then();
    }
}
