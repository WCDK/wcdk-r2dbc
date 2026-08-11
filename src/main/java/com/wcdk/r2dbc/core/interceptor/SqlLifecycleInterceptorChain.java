package com.wcdk.r2dbc.core.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

/**
 * SQL生命周期拦截器链管理器。
 * <p>
 * 负责管理和执行所有注册的拦截器，支持同步和异步两种模式：
 * <ul>
 *     <li>{@link SqlLifecycleInterceptor} - 同步拦截器</li>
 *     <li>{@link ReactiveSqlLifecycleInterceptor} - 异步拦截器</li>
 * </ul>
 * <p>
 * 执行顺序：异步拦截器在同步拦截器之前执行。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class SqlLifecycleInterceptorChain {

    private static final Logger log = LoggerFactory.getLogger(SqlLifecycleInterceptorChain.class);

    private final List<SqlLifecycleInterceptor> syncInterceptors;

    private final List<ReactiveSqlLifecycleInterceptor> reactiveInterceptors;

    public SqlLifecycleInterceptorChain(List<SqlLifecycleInterceptor> syncInterceptors,
                                        List<ReactiveSqlLifecycleInterceptor> reactiveInterceptors) {
        this.syncInterceptors = syncInterceptors != null
                ? syncInterceptors.stream()
                    .sorted(Comparator.comparingInt(SqlLifecycleInterceptor::getOrder))
                    .toList()
                : List.of();
        this.reactiveInterceptors = reactiveInterceptors != null
                ? reactiveInterceptors.stream()
                    .sorted(Comparator.comparingInt(ReactiveSqlLifecycleInterceptor::getOrder))
                    .toList()
                : List.of();
    }

    /**
     * 执行SQL编译前拦截（响应式）。
     *
     * @param context SQL执行上下文
     * @return 是否终止后续执行
     */
    public Mono<Boolean> beforeCompileReactive(SqlExecutionContext context) {
        // 先执行异步拦截器
        return executeReactiveInterceptors(reactiveInterceptors,
                interceptor -> interceptor.beforeCompileReactive(context), "beforeCompileReactive", context)
                .then(Mono.defer(() -> {
                    // 如果已终止，直接返回
                    if (context.isTerminated()) {
                        return Mono.just(true);
                    }
                    // 再执行同步拦截器
                    return Mono.fromCallable(() -> executeSyncInterceptors(syncInterceptors,
                            SqlLifecycleInterceptor::beforeCompile, "beforeCompile", context));
                }));
    }

    /**
     * 执行SQL编译后拦截（响应式）。
     *
     * @param context SQL执行上下文
     * @return 是否终止后续执行
     */
    public Mono<Boolean> afterCompileReactive(SqlExecutionContext context) {
        return executeReactiveInterceptors(reactiveInterceptors,
                interceptor -> interceptor.afterCompileReactive(context), "afterCompileReactive", context)
                .then(Mono.defer(() -> {
                    if (context.isTerminated()) {
                        return Mono.just(true);
                    }
                    return Mono.fromCallable(() -> executeSyncInterceptors(syncInterceptors,
                            SqlLifecycleInterceptor::afterCompile, "afterCompile", context));
                }));
    }

    /**
     * 执行SQL执行前拦截（响应式）。
     *
     * @param context SQL执行上下文
     * @return 是否终止后续执行
     */
    public Mono<Boolean> beforeExecuteReactive(SqlExecutionContext context) {
        return executeReactiveInterceptors(reactiveInterceptors,
                interceptor -> interceptor.beforeExecuteReactive(context), "beforeExecuteReactive", context)
                .then(Mono.defer(() -> {
                    if (context.isTerminated()) {
                        return Mono.just(true);
                    }
                    return Mono.fromCallable(() -> executeSyncInterceptors(syncInterceptors,
                            SqlLifecycleInterceptor::beforeExecute, "beforeExecute", context));
                }));
    }

    /**
     * 执行SQL执行后拦截（响应式）。
     *
     * @param context SQL执行上下文
     * @return 完成信号
     */
    public Mono<Void> afterExecuteReactive(SqlExecutionContext context) {
        return executeReactiveInterceptors(reactiveInterceptors,
                interceptor -> interceptor.afterExecuteReactive(context), "afterExecuteReactive", context)
                .then(Mono.fromRunnable(() -> executeSyncAfterExecute(syncInterceptors, context)));
    }

    /**
     * 执行异步拦截器链。
     */
    private Mono<Boolean> executeReactiveInterceptors(List<ReactiveSqlLifecycleInterceptor> interceptors,
                                                      ReactiveInterceptorAction action,
                                                      String phase,
                                                      SqlExecutionContext context) {
        if (interceptors.isEmpty()) {
            return Mono.just(false);
        }

        return Flux.fromIterable(interceptors)
                .concatMap(interceptor -> action.execute(interceptor)
                        .doOnSubscribe(s -> log.trace("Executing interceptor [{}] {}", interceptor.getClass().getSimpleName(), phase))
                        .doOnError(e -> log.error("拦截器 [{}] 在 {} 阶段出错", interceptor.getClass().getSimpleName(), phase, e))
                        .then(Mono.fromSupplier(context::isTerminated))
                )
                .takeUntil(Boolean::booleanValue)
                .then(Mono.fromSupplier(context::isTerminated));
    }

    /**
     * 执行同步拦截器链（用于before*阶段）。
     */
    private boolean executeSyncInterceptors(List<SqlLifecycleInterceptor> interceptors,
                                            SyncInterceptorAction action,
                                            String phase,
                                            SqlExecutionContext context) {
        for (SqlLifecycleInterceptor interceptor : interceptors) {
            action.execute(interceptor, context);
            if (context.isTerminated()) {
                log.debug("拦截器 [{}] 在 {} 阶段终止执行: status={}, reason={}",
                        interceptor.getClass().getSimpleName(), phase,
                        context.getStatus(), context.getStatusReason());
                return true;
            }
        }
        return false;
    }

    /**
     * 执行同步拦截器（用于after*阶段）。
     */
    private void executeSyncAfterExecute(List<SqlLifecycleInterceptor> interceptors, SqlExecutionContext context) {
        for (SqlLifecycleInterceptor interceptor : interceptors) {
            interceptor.afterExecute(context);
        }
    }

    /**
     * 获取同步拦截器数量。
     *
     * @return 同步拦截器数量
     */
    public int size() {
        return syncInterceptors.size() + reactiveInterceptors.size();
    }

    /**
     * 判断是否有拦截器。
     *
     * @return 是否有拦截器
     */
    public boolean isEmpty() {
        return syncInterceptors.isEmpty() && reactiveInterceptors.isEmpty();
    }

    /**
     * 函数式接口：异步拦截器操作。
     */
    @FunctionalInterface
    private interface ReactiveInterceptorAction {
        Mono<Void> execute(ReactiveSqlLifecycleInterceptor interceptor);
    }

    /**
     * 函数式接口：同步拦截器操作。
     */
    @FunctionalInterface
    private interface SyncInterceptorAction {
        void execute(SqlLifecycleInterceptor interceptor, SqlExecutionContext context);
    }
}
