package com.wcdk.r2dbc.datasource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;

/**
 * R2DBC 数据源上下文工具。
 *
 * @author WCDK
 */
public final class R2dbcDataSourceContext {

    static final Class<R2dbcDataSourceContext> CONTEXT_KEY = R2dbcDataSourceContext.class;
    private static final String TRANSACTION_KEY = R2dbcDataSourceContext.class.getName() + ".transaction";
    private static final String PRIMARY = "<primary>";
    private static final Object TRANSACTION_RESOURCE_KEY =
            R2dbcDataSourceContext.class.getName() + ".transaction-resource";

    private R2dbcDataSourceContext() {
    }

    public static <T> Mono<T> use(String dataSource, Mono<T> publisher) {
        String key = requireDataSource(dataSource);
        return Mono.deferContextual(context -> {
                    assertTransactionDataSource(context, key);
                    return assertSpringTransactionDataSource(key, publisher);
                })
                .contextWrite(context -> context.put(CONTEXT_KEY, key));
    }

    public static <T> Flux<T> use(String dataSource, Flux<T> publisher) {
        String key = requireDataSource(dataSource);
        return Flux.deferContextual(context -> {
                    assertTransactionDataSource(context, key);
                    return assertSpringTransactionDataSource(key, publisher);
                })
                .contextWrite(context -> context.put(CONTEXT_KEY, key));
    }

    /**
     * 将选库和 Spring 事务传播放在同一作用域。
     * <p>目标键在获取新事务连接前可见；校验在 operator 完成挂起/加入后执行。
     * REQUIRES_NEW 使用新事务的钉扎，NOT_SUPPORTED 放行，REQUIRED 仍拒绝跨库加入。
     * 手动事务钉扎不由 Spring 管理，仍在进入此作用域前校验。
     */
    public static <T> Mono<T> use(String dataSource,
                                  org.springframework.transaction.reactive.TransactionalOperator operator,
                                  Mono<T> publisher) {
        String key = requireDataSource(dataSource);
        java.util.Objects.requireNonNull(operator, "事务操作符不能为空");
        java.util.Objects.requireNonNull(publisher, "业务流不能为空");
        return Mono.deferContextual(context -> {
                    assertTransactionDataSource(context, key);
                    return operator.transactional(use(key, publisher));
                })
                .contextWrite(context -> context.put(CONTEXT_KEY, key));
    }

    /** {@link #use(String, org.springframework.transaction.reactive.TransactionalOperator, Mono)} 的流式入口。 */
    public static <T> Flux<T> use(String dataSource,
                                  org.springframework.transaction.reactive.TransactionalOperator operator,
                                  Flux<T> publisher) {
        String key = requireDataSource(dataSource);
        java.util.Objects.requireNonNull(operator, "事务操作符不能为空");
        java.util.Objects.requireNonNull(publisher, "业务流不能为空");
        return Flux.deferContextual(context -> {
                    assertTransactionDataSource(context, key);
                    return operator.transactional(use(key, publisher));
                })
                .contextWrite(context -> context.put(CONTEXT_KEY, key));
    }

    public static <T> Mono<T> pinTransactionDataSource(Mono<T> publisher) {
        return pinTransactionDataSource(publisher, PRIMARY);
    }

    public static <T> Flux<T> pinTransactionDataSource(Flux<T> publisher) {
        return pinTransactionDataSource(publisher, PRIMARY);
    }

    /** 手动事务作用域使用真实主键；单连接工厂传 null，选键不改变物理连接。 */
    public static <T> Mono<T> pinTransactionDataSource(Mono<T> publisher, String primary) {
        return Mono.deferContextual(context -> primary == null ? publisher : publisher.contextWrite(current ->
                current.put(TRANSACTION_KEY, get(context) == null ? primary : get(context))));
    }

    public static <T> Flux<T> pinTransactionDataSource(Flux<T> publisher, String primary) {
        return Flux.deferContextual(context -> primary == null ? publisher : publisher.contextWrite(current ->
                current.put(TRANSACTION_KEY, get(context) == null ? primary : get(context))));
    }

    public static String get(ContextView contextView) {
        return contextView.getOrDefault(CONTEXT_KEY, null);
    }

    /**
     * 返回 Spring 响应式事务中用于锁定数据源的资源键。
     * @author wcdk
     */
    public static Object transactionResourceKey() {
        return TRANSACTION_RESOURCE_KEY;
    }

    /**
     * 返回当前上下文的数据源；未指定时使用主数据源占位键。
     * @author wcdk
     */
    public static String currentOrPrimary(ContextView contextView) {
        String dataSource = get(contextView);
        return dataSource == null ? PRIMARY : dataSource;
    }

    static String requireDataSource(String dataSource) {
        if (dataSource == null || dataSource.isBlank()) {
            throw new IllegalArgumentException("R2DBC数据源键为空");
        }
        return dataSource;
    }

    /**
     * 在连接工厂边界登记并校验事务选定的数据源。
     * <p>
     * 首次获取事务连接时登记数据源，后续无论事务由 Spring Advisor、WCDK 切面、
     * {@code TransactionalOperator} 还是用户提供的响应式事务管理器发起，均使用同一规则校验。
     * 非事务调用没有 {@link TransactionSynchronizationManager}，直接放行。
     *
     * @param context 当前 Reactor 上下文
     * @param requested 路由工厂即将使用的数据源
     * @param primary 路由工厂的主数据源
     * @return 校验完成信号
     */
    static Mono<Void> guardTransactionDataSource(ContextView context, String requested, String primary) {
        assertTransactionDataSource(context, requested, primary);
        return assertSpringTransactionDataSource(requested, Mono.<Void>empty());
    }

    private static void assertTransactionDataSource(ContextView context, String requested, String primary) {
        String pinned = context.getOrDefault(TRANSACTION_KEY, null);
        String canonical = PRIMARY.equals(pinned) ? primary : pinned;
        if (canonical != null && !canonical.equals(requested)) {
            throw transactionDataSourceSwitchException(canonical, requested);
        }
    }

    private static void assertTransactionDataSource(ContextView context, String requested) {
        // 历史入口仅知道默认主键；框架入口均传入路由工厂的真实主键。
        assertTransactionDataSource(context, requested, "primary");
    }

    /** 查找阶段恢复为空，业务错误始终原样传播，不重放业务流。 */
    public static Mono<TransactionSynchronizationManager> currentManager() {
        return org.springframework.transaction.reactive.TransactionContextManager.currentContext()
                .map(context -> {
                    TransactionSynchronizationManager manager = new TransactionSynchronizationManager(context);
                    if (manager.isActualTransactionActive() && !manager.hasResource(TRANSACTION_RESOURCE_KEY)) {
                        for (var resource : context.getResources().entrySet()) {
                            if (resource.getKey() instanceof DynamicRoutingConnectionFactory routing
                                    && resource.getValue() instanceof org.springframework.r2dbc.connection.ConnectionHolder holder) {
                                String selected = routing.dataSourceOf(holder.getConnection());
                                if (selected != null) {
                                    bindSpringDataSource(manager, selected);
                                    break;
                                }
                            }
                        }
                    }
                    return manager;
                })
                .onErrorResume(NoTransactionException.class, ignored -> Mono.empty());
    }

    private static <T> Mono<T> assertSpringTransactionDataSource(String requested, Mono<T> publisher) {
        return currentManager().doOnNext(manager -> verifySpringDataSource(manager, requested))
                .then(publisher);
    }

    private static <T> Flux<T> assertSpringTransactionDataSource(String requested, Flux<T> publisher) {
        return currentManager().doOnNext(manager -> verifySpringDataSource(manager, requested))
                .thenMany(publisher);
    }

    /** 标准/自定义 R2DBC 管理器的钉扎来源是当前实际事务持有的连接。 */
    public static String springTransactionDataSource(TransactionSynchronizationManager manager) {
        if (!manager.isActualTransactionActive()) return null;
        Object explicit = manager.getResource(TRANSACTION_RESOURCE_KEY);
        if (explicit != null) return explicit.toString();
        return null;
    }

    public static void verifySpringDataSource(TransactionSynchronizationManager manager, String requested) {
        String pinned = springTransactionDataSource(manager);
        if (pinned != null && !PRIMARY.equals(pinned) && !pinned.equals(requested)) {
            throw transactionDataSourceSwitchException(pinned, requested);
        }
    }

    /** 钉扎随 Spring 事务挂起/恢复，不能遗留到无事务或新事务窗口。 */
    public static void bindSpringDataSource(TransactionSynchronizationManager manager, String dataSource) {
        if (!manager.isActualTransactionActive() || dataSource == null || PRIMARY.equals(dataSource)) return;
        verifySpringDataSource(manager, dataSource);
        if (manager.hasResource(TRANSACTION_RESOURCE_KEY)) return;
        manager.bindResource(TRANSACTION_RESOURCE_KEY, dataSource);
        if (manager.isSynchronizationActive()) {
            manager.registerSynchronization(new org.springframework.transaction.reactive.TransactionSynchronization() {
                @Override
                public Mono<Void> suspend() {
                    return Mono.fromRunnable(() -> manager.unbindResourceIfPossible(TRANSACTION_RESOURCE_KEY));
                }
                @Override
                public Mono<Void> resume() {
                    return Mono.fromRunnable(() -> manager.bindResource(TRANSACTION_RESOURCE_KEY, dataSource));
                }
                @Override
                public Mono<Void> afterCompletion(int completionStatus) {
                    return Mono.fromRunnable(() -> manager.unbindResourceIfPossible(TRANSACTION_RESOURCE_KEY));
                }
            });
        }
    }

    /**
     * 创建统一的数据源切换异常。
     * @author wcdk
     */
    public static IllegalStateException transactionDataSourceSwitchException(String pinned, String requested) {
        return new IllegalStateException("事务开始后无法将 R2DBC 数据源从 " + pinned + " 切换到 " + requested + "：事务内禁止切换数据源");
    }
}
