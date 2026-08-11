package com.wcdk.r2dbc.core.executor;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.interceptor.SqlTerminationType;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * R2DBC SQL生命周期执行器，负责管理SQL执行的生命周期（编译前、编译后、执行前、执行后）。
 * <p>
 * 使用异步方法管理拦截器链：
 * {@link #beforeCompileReactive}、{@link #afterCompileReactive}、
 * {@link #beforeExecuteReactive}、{@link #afterExecuteReactive}
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class SqlLifecycleExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlLifecycleExecutor.class);

    private final SqlLifecycleInterceptorChain chain;
    private final SqlExecutionObserver observer;

    public SqlLifecycleExecutor() {
        this(new SqlLifecycleInterceptorChain(java.util.List.of(), java.util.List.of()), SqlExecutionObserver.NOOP);
    }

    public SqlLifecycleExecutor(SqlLifecycleInterceptorChain chain) {
        this(chain, SqlExecutionObserver.NOOP);
    }

    public SqlLifecycleExecutor(SqlLifecycleInterceptorChain chain, SqlExecutionObserver observer) {
        this.chain = java.util.Objects.requireNonNull(chain);
        this.observer = java.util.Objects.requireNonNull(observer);
    }

    /**
     * 创建SQL执行上下文。
     *
     * @param methodName 方法名
     * @param parameters 参数
     * @return SQL执行上下文
     */
    public SqlExecutionContext createContext(String methodName, Map<String, Object> parameters) {
        SqlExecutionContext context = new SqlExecutionContext(findMethod(methodName), R2dbcUtil.class, null);
        context.setParameters(parameters);
        return context;
    }

    // ==================== 异步方法 ====================

    /**
     * 执行编译前拦截（异步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public Mono<Boolean> beforeCompileReactive(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.beforeCompileReactive(context);
    }

    /**
     * 执行编译后拦截（异步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public Mono<Boolean> afterCompileReactive(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.afterCompileReactive(context);
    }

    /**
     * 执行执行前拦截（异步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public Mono<Boolean> beforeExecuteReactive(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.beforeExecuteReactive(context);
    }

    /**
     * 执行执行后拦截（异步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 完成信号
     */
    public Mono<Void> afterExecuteReactive(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.afterExecuteReactive(context);
    }

    /** Runs all pre-execution phases, stopping as soon as an interceptor terminates execution. */
    public Mono<Boolean> prepare(SqlLifecycleInterceptorChain chain,
                                 SqlExecutionContext context,
                                 Supplier<? extends Mono<?>> compileAction) {
        return observe(SqlExecutionPhase.PREPARE, context, chain.beforeCompileReactive(context))
                .flatMap(terminated -> terminated
                        ? Mono.just(true)
                        : observe(SqlExecutionPhase.COMPILE, context, Mono.defer(compileAction).then())
                        .then(Mono.defer(() -> context.isTerminated()
                                ? Mono.just(true)
                                : observe(SqlExecutionPhase.REWRITE, context, chain.afterCompileReactive(context)))))
                .flatMap(terminated -> terminated
                        ? Mono.just(true)
                        : observe(SqlExecutionPhase.VALIDATE, context, chain.beforeExecuteReactive(context)));
    }

    /** Executes a single-result statement and awaits afterExecute in the same chain. */
    public <T> Mono<T> executeMono(SqlLifecycleInterceptorChain chain,
                                   SqlExecutionContext context,
                                   Mono<Boolean> preparation,
                                   Supplier<? extends Mono<T>> statement) {
        return Mono.defer(() -> preparation.flatMap(terminated -> terminated
                ? terminatedMono(context)
                : executeAndFinalizeMono(chain, context, statement)));
    }

    /** Executes a multi-result statement and awaits afterExecute in the same chain. */
    public <T> Flux<T> executeFlux(SqlLifecycleInterceptorChain chain,
                                   SqlExecutionContext context,
                                   Mono<Boolean> preparation,
                                   Supplier<? extends Publisher<T>> statement) {
        return Flux.defer(() -> preparation.flatMapMany(terminated -> terminated
                ? terminatedFlux(context)
                : executeAndFinalizeFlux(chain, context, statement)));
    }

    private <T> Mono<T> executeAndFinalizeMono(SqlLifecycleInterceptorChain chain,
                                                SqlExecutionContext context,
                                                Supplier<? extends Mono<T>> statement) {
        AtomicBoolean finalized = new AtomicBoolean();
        AtomicLong resultCount = new AtomicLong();
        Mono<T> observed = Mono.defer(() -> {
                    context.setStartTime(System.nanoTime());
                    return observe(SqlExecutionPhase.EXECUTE, context, statement.get());
                })
                .materialize()
                .flatMap(signal -> finalizeMonoSignal(chain, context, signal, finalized, resultCount))
                .dematerialize();
        return Mono.usingWhen(Mono.just(finalized), ignored -> observed,
                ignored -> Mono.empty(),
                (ignored, error) -> Mono.empty(),
                ignored -> finalizeCancellation(chain, context, finalized, resultCount));
    }

    private <T> Flux<T> executeAndFinalizeFlux(SqlLifecycleInterceptorChain chain,
                                                SqlExecutionContext context,
                                                Supplier<? extends Publisher<T>> statement) {
        AtomicBoolean finalized = new AtomicBoolean();
        AtomicLong resultCount = new AtomicLong();
        Flux<T> observed = Flux.defer(() -> {
                    context.setStartTime(System.nanoTime());
                    return observer.observeFlux(SqlExecutionPhase.EXECUTE, context, Flux.from(statement.get()));
                })
                .doOnNext(ignored -> resultCount.incrementAndGet())
                .materialize()
                .concatMap(signal -> finalizeFluxSignal(chain, context, signal, finalized, resultCount))
                .dematerialize();
        return Flux.usingWhen(Mono.just(finalized), ignored -> observed,
                ignored -> Mono.empty(),
                (ignored, error) -> Mono.empty(),
                ignored -> finalizeCancellation(chain, context, finalized, resultCount));
    }

    private <T> Mono<Signal<T>> finalizeMonoSignal(SqlLifecycleInterceptorChain chain,
                                                    SqlExecutionContext context,
                                                    Signal<T> signal,
                                                    AtomicBoolean finalized,
                                                    AtomicLong resultCount) {
        if (!finalized.compareAndSet(false, true)) {
            return Mono.just(signal);
        }
        if (signal.isOnNext()) {
            context.setResult(signal.get());
            resultCount.set(monoResultCount(context, signal.get()));
            context.setEmittedItemCount(1);
            if (isDataModification(context) && signal.get() instanceof Number number) {
                context.setAffectedRowCount(number.longValue());
            } else {
                context.setReturnedRowCount(1);
            }
        } else if (signal.isOnError()) {
            context.setError(signal.getThrowable());
        }
        context.setTerminationType(signal.isOnError() ? SqlTerminationType.ERROR : SqlTerminationType.COMPLETE);
        context.setResultCount(resultCount.get());
        context.setEndTime(System.nanoTime());
        return runAfterExecute(chain, context, signal.getThrowable()).thenReturn(signal);
    }

    private <T> Mono<Signal<T>> finalizeFluxSignal(SqlLifecycleInterceptorChain chain,
                                                    SqlExecutionContext context,
                                                    Signal<T> signal,
                                                    AtomicBoolean finalized,
                                                    AtomicLong resultCount) {
        if (signal.isOnNext() || !finalized.compareAndSet(false, true)) {
            return Mono.just(signal);
        }
        if (signal.isOnError()) {
            context.setError(signal.getThrowable());
        }
        context.setTerminationType(signal.isOnError() ? SqlTerminationType.ERROR : SqlTerminationType.COMPLETE);
        context.setResultCount(resultCount.get());
        context.setEmittedItemCount(resultCount.get());
        context.setReturnedRowCount(resultCount.get());
        context.setEndTime(System.nanoTime());
        return runAfterExecute(chain, context, signal.getThrowable()).thenReturn(signal);
    }

    private Mono<Void> finalizeCancellation(SqlLifecycleInterceptorChain chain,
                                            SqlExecutionContext context,
                                            AtomicBoolean finalized,
                                            AtomicLong resultCount) {
        if (!finalized.compareAndSet(false, true)) {
            return Mono.empty();
        }
        context.setTerminationType(SqlTerminationType.CANCELLED);
        context.setResultCount(resultCount.get());
        context.setEmittedItemCount(resultCount.get());
        if (!isDataModification(context)) {
            context.setReturnedRowCount(resultCount.get());
        }
        context.setEndTime(System.nanoTime());
        return runAfterExecute(chain, context, null)
                .doOnError(error -> log.error(
                        "SQL cancellation cleanup failed: executionId={}, statementId={}, errorType={}",
                        context.getExecutionId(), context.getStatementId(), error.getClass().getName(), error));
    }

    private long monoResultCount(SqlExecutionContext context, Object result) {
        if (result == null) {
            return 0;
        }
        if (result instanceof Number number && isDataModification(context)) {
            return number.longValue();
        }
        return 1;
    }

    private boolean isDataModification(SqlExecutionContext context) {
        return switch (context.getCommandType()) {
            case INSERT, UPDATE, DELETE, MERGE -> true;
            case SELECT, UNKNOWN -> false;
        };
    }

    private Mono<Void> runAfterExecute(SqlLifecycleInterceptorChain chain,
                                       SqlExecutionContext context,
                                       Throwable businessError) {
        return observe(SqlExecutionPhase.FINALLY, context, chain.afterExecuteReactive(context))
                .onErrorResume(afterError -> {
                    if (businessError != null) {
                        businessError.addSuppressed(afterError);
                        log.warn("SQL finally error suppressed by business error: executionId={}, statementId={}, "
                                        + "businessErrorType={}, suppressedErrorType={}",
                                context.getExecutionId(), context.getStatementId(),
                                businessError.getClass().getName(), afterError.getClass().getName(), afterError);
                        return Mono.error(businessError);
                    }
                    return Mono.error(afterError);
                });
    }

    private <T> Mono<T> observe(SqlExecutionPhase phase, SqlExecutionContext context, Mono<T> action) {
        return observer.observe(phase, context, action);
    }

    @SuppressWarnings("unchecked")
    private <T> Mono<T> terminatedMono(SqlExecutionContext context) {
        if (context.getResult() == null) {
            return Mono.error(terminationWithoutResult(context));
        }
        return Mono.justOrEmpty((T) context.getResult());
    }

    @SuppressWarnings("unchecked")
    private <T> Flux<T> terminatedFlux(SqlExecutionContext context) {
        Object result = context.getResult();
        if (result == null) {
            return Flux.error(terminationWithoutResult(context));
        }
        if (result instanceof Publisher<?> publisher) {
            return Flux.from((Publisher<T>) publisher);
        }
        if (result instanceof Iterable<?> iterable) {
            return Flux.fromIterable((Iterable<T>) iterable);
        }
        if (result.getClass().isArray()) {
            return Flux.range(0, Array.getLength(result)).map(index -> (T) Array.get(result, index));
        }
        return Flux.just((T) result);
    }

    private IllegalStateException terminationWithoutResult(SqlExecutionContext context) {
        return new IllegalStateException("SQL execution was terminated without an explicit result: status="
                + context.getStatus() + ", reason=" + context.getStatusReason());
    }

    /**
     * 获取拦截器链。
     *
     * @return 拦截器链
     */
    public SqlLifecycleInterceptorChain getChain() {
        return chain;
    }

    private Method findMethod(String methodName) {
        try {
            return R2dbcUtil.class.getMethod(methodName, String.class, Map.class);
        } catch (NoSuchMethodException e) {
            try {
                return R2dbcUtil.class.getDeclaredMethod(methodName, String.class, Map.class);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("方法未找到: " + methodName, ex);
            }
        }
    }
}
