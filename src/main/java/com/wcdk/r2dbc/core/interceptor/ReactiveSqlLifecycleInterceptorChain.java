package com.wcdk.r2dbc.core.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

/**
 * 响应式SQL生命周期拦截器链管理器。
 * <p>
 * 负责管理和执行所有注册的 {@link ReactiveSqlLifecycleInterceptor}。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class ReactiveSqlLifecycleInterceptorChain {

    private static final Logger log = LoggerFactory.getLogger(ReactiveSqlLifecycleInterceptorChain.class);

    private final List<ReactiveSqlLifecycleInterceptor> interceptors;

    public ReactiveSqlLifecycleInterceptorChain(List<ReactiveSqlLifecycleInterceptor> interceptors) {
        this.interceptors = interceptors != null
                ? interceptors.stream()
                    .sorted(Comparator.comparingInt(ReactiveSqlLifecycleInterceptor::getOrder))
                    .toList()
                : List.of();
    }

    /**
     * 执行SQL编译前拦截（异步）。
     *
     * @param context SQL执行上下文
     * @return 是否终止后续执行
     */
    public Mono<Boolean> beforeCompile(SqlExecutionContext context) {
        if (interceptors.isEmpty()) {
            return Mono.just(false);
        }

        return Flux.fromIterable(interceptors)
                .concatMap(interceptor -> interceptor.beforeCompileReactive(context)
                        .doOnSubscribe(s -> log.trace("Executing interceptor [{}] beforeCompileReactive",
                                interceptor.getClass().getSimpleName()))
                        .doOnError(e -> log.error("Interceptor [{}] error at beforeCompileReactive",
                                interceptor.getClass().getSimpleName(), e))
                        .then(Mono.fromSupplier(() -> {
                            if (context.isTerminated()) {
                                log.debug("Interceptor [{}] terminated execution at beforeCompile: status={}, reason={}",
                                        interceptor.getClass().getSimpleName(),
                                        context.getStatus(),
                                        context.getStatusReason());
                                return true;
                            }
                            return false;
                        }))
                )
                .filter(Boolean::booleanValue)
                .next()
                .defaultIfEmpty(false);
    }

    /**
     * 执行SQL编译后拦截（异步）。
     *
     * @param context SQL执行上下文
     * @return 是否终止后续执行
     */
    public Mono<Boolean> afterCompile(SqlExecutionContext context) {
        if (interceptors.isEmpty()) {
            return Mono.just(false);
        }

        return Flux.fromIterable(interceptors)
                .concatMap(interceptor -> interceptor.afterCompileReactive(context)
                        .doOnSubscribe(s -> log.trace("Executing interceptor [{}] afterCompileReactive",
                                interceptor.getClass().getSimpleName()))
                        .doOnError(e -> log.error("Interceptor [{}] error at afterCompileReactive",
                                interceptor.getClass().getSimpleName(), e))
                        .then(Mono.fromSupplier(() -> {
                            if (context.isTerminated()) {
                                log.debug("Interceptor [{}] terminated execution at afterCompile: status={}, reason={}",
                                        interceptor.getClass().getSimpleName(),
                                        context.getStatus(),
                                        context.getStatusReason());
                                return true;
                            }
                            return false;
                        }))
                )
                .filter(Boolean::booleanValue)
                .next()
                .defaultIfEmpty(false);
    }

    /**
     * 执行SQL执行前拦截（异步）。
     *
     * @param context SQL执行上下文
     * @return 是否终止后续执行
     */
    public Mono<Boolean> beforeExecute(SqlExecutionContext context) {
        if (interceptors.isEmpty()) {
            return Mono.just(false);
        }

        return Flux.fromIterable(interceptors)
                .concatMap(interceptor -> interceptor.beforeExecuteReactive(context)
                        .doOnSubscribe(s -> log.trace("Executing interceptor [{}] beforeExecuteReactive",
                                interceptor.getClass().getSimpleName()))
                        .doOnError(e -> log.error("Interceptor [{}] error at beforeExecuteReactive",
                                interceptor.getClass().getSimpleName(), e))
                        .then(Mono.fromSupplier(() -> {
                            if (context.isTerminated()) {
                                log.debug("Interceptor [{}] terminated execution at beforeExecute: status={}, reason={}",
                                        interceptor.getClass().getSimpleName(),
                                        context.getStatus(),
                                        context.getStatusReason());
                                return true;
                            }
                            return false;
                        }))
                )
                .filter(Boolean::booleanValue)
                .next()
                .defaultIfEmpty(false);
    }

    /**
     * 执行SQL执行后拦截（异步）。
     *
     * @param context SQL执行上下文
     * @return 完成信号
     */
    public Mono<Void> afterExecute(SqlExecutionContext context) {
        if (interceptors.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(interceptors)
                .concatMap(interceptor -> interceptor.afterExecuteReactive(context)
                        .doOnSubscribe(s -> log.trace("Executing interceptor [{}] afterExecuteReactive",
                                interceptor.getClass().getSimpleName()))
                        .doOnError(e -> log.error("Interceptor [{}] error at afterExecuteReactive",
                                interceptor.getClass().getSimpleName(), e))
                )
                .then();
    }

    /**
     * 获取拦截器数量。
     *
     * @return 拦截器数量
     */
    public int size() {
        return interceptors.size();
    }

    /**
     * 判断是否有拦截器。
     *
     * @return 是否有拦截器
     */
    public boolean isEmpty() {
        return interceptors.isEmpty();
    }
}
