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
 **/
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

    public static <T> Mono<T> pinTransactionDataSource(Mono<T> publisher) {
        return Mono.deferContextual(context -> {
            String dataSource = get(context);
            String pinned = dataSource == null ? PRIMARY : dataSource;
            return publisher.contextWrite(current -> current.put(TRANSACTION_KEY, pinned));
        });
    }

    public static <T> Flux<T> pinTransactionDataSource(Flux<T> publisher) {
        return Flux.deferContextual(context -> {
            String dataSource = get(context);
            String pinned = dataSource == null ? PRIMARY : dataSource;
            return publisher.contextWrite(current -> current.put(TRANSACTION_KEY, pinned));
        });
    }

    public static String get(ContextView contextView) {
        return contextView.getOrDefault(CONTEXT_KEY, null);
    }

    /***
     * 返回 Spring 响应式事务中用于锁定数据源的资源键。
     * @author wcdk
     */
    public static Object transactionResourceKey() {
        return TRANSACTION_RESOURCE_KEY;
    }

    /***
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

    private static void assertTransactionDataSource(ContextView context, String requested) {
        String pinned = context.getOrDefault(TRANSACTION_KEY, null);
        if (pinned != null && !pinned.equals(requested)) {
            throw new IllegalStateException("事务开始后无法将R2DBC数据源从 "
                    + pinned + " 切换到 " + requested);
        }
    }
    /***
     * 校验标准 Spring 响应式事务登记的数据源，防止事务中跨数据源执行 SQL。
     * @author wcdk
     */
    private static <T> Mono<T> assertSpringTransactionDataSource(String requested, Mono<T> publisher) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(manager -> {
                    Object pinned = manager.getResource(TRANSACTION_RESOURCE_KEY);
                    if (pinned != null && !pinned.equals(requested)) {
                        return Mono.error(transactionDataSourceSwitchException(pinned.toString(), requested));
                    }
                    return publisher;
                })
                .onErrorResume(NoTransactionException.class, ignored -> publisher);
    }

    /***
     * 校验 Flux 类型响应式事务的数据源。
     * @author wcdk
     */
    private static <T> Flux<T> assertSpringTransactionDataSource(String requested, Flux<T> publisher) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMapMany(manager -> {
                    Object pinned = manager.getResource(TRANSACTION_RESOURCE_KEY);
                    if (pinned != null && !pinned.equals(requested)) {
                        return Flux.error(transactionDataSourceSwitchException(pinned.toString(), requested));
                    }
                    return publisher;
                })
                .onErrorResume(NoTransactionException.class, ignored -> publisher);
    }
    /***
     * 创建统一的数据源切换异常。
     * @author wcdk
     */
    public static IllegalStateException transactionDataSourceSwitchException(String pinned, String requested) {
        return new IllegalStateException("事务开始后无法将 R2DBC 数据源从 " + pinned + " 切换到 " + requested);
    }
}