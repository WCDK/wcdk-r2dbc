package com.wcdk.r2dbc.core.executor;

import com.wcdk.r2dbc.core.interceptor.ReactiveSqlLifecycleInterceptor;
import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.xml.SqlCommandType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlLifecycleExecutorTests {

    private final SqlLifecycleExecutor executor = new SqlLifecycleExecutor();

    @Test
    void waitsForAfterExecuteAndPreservesReactorContext() {
        AtomicBoolean afterCompleted = new AtomicBoolean();
        AtomicReference<String> traceId = new AtomicReference<>();
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> afterExecuteReactive(SqlExecutionContext context) {
                return Mono.deferContextual(view -> {
                    traceId.set(view.get("traceId"));
                    assertEquals("value", context.getResult());
                    return Mono.delay(Duration.ofMillis(10))
                            .then(Mono.fromRunnable(() -> afterCompleted.set(true)));
                });
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);

        Mono<String> result = executor.executeMono(chain, context,
                executor.prepare(chain, context, Mono::empty),
                () -> Mono.just("value"));

        StepVerifier.create(result.contextWrite(Context.of("traceId", "trace-1")))
                .expectNext("value")
                .verifyComplete();

        assertTrue(afterCompleted.get());
        assertEquals("trace-1", traceId.get());
        assertEquals(1, context.getResultCount());
    }

    @Test
    void keepsBusinessErrorAndSuppressesAfterExecuteError() {
        IllegalStateException businessError = new IllegalStateException("business");
        IllegalArgumentException afterError = new IllegalArgumentException("after");
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> afterExecuteReactive(SqlExecutionContext context) {
                return Mono.error(afterError);
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);

        Mono<String> result = executor.executeMono(chain, context,
                executor.prepare(chain, context, Mono::empty),
                () -> Mono.error(businessError));

        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    assertSame(businessError, error);
                    assertSame(afterError, error.getSuppressed()[0]);
                    assertSame(businessError, context.getError());
                })
                .verify();
    }

    @Test
    void propagatesAfterExecuteErrorOnSuccessfulStatement() {
        IllegalStateException afterError = new IllegalStateException("after");
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> afterExecuteReactive(SqlExecutionContext context) {
                return Mono.error(afterError);
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);

        StepVerifier.create(executor.executeMono(chain, context,
                        executor.prepare(chain, context, Mono::empty),
                        () -> Mono.just("value")))
                .expectErrorSatisfies(error -> assertSame(afterError, error))
                .verify();
    }

    @Test
    void invokesAfterExecuteOnCancellationWithReactorContext() throws InterruptedException {
        CountDownLatch callback = new CountDownLatch(1);
        AtomicReference<String> traceId = new AtomicReference<>();
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> afterExecuteReactive(SqlExecutionContext context) {
                return Mono.deferContextual(view -> Mono.fromRunnable(() -> {
                    traceId.set(view.get("traceId"));
                    callback.countDown();
                }));
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);

        Flux<String> result = executor.executeFlux(chain, context,
                executor.prepare(chain, context, Mono::empty), Flux::never);

        StepVerifier.create(result.contextWrite(Context.of("traceId", "cancel-trace")))
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();

        assertTrue(callback.await(1, TimeUnit.SECONDS));
        assertEquals("cancel-trace", traceId.get());
    }

    @Test
    void returnsExplicitInterceptorResultWithoutExecutingStatement() {
        AtomicBoolean executed = new AtomicBoolean();
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
                context.cacheHit("cached");
                return Mono.empty();
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);

        Mono<String> result = executor.executeMono(chain, context,
                executor.prepare(chain, context, Mono::empty),
                () -> Mono.fromSupplier(() -> {
                    executed.set(true);
                    return "database";
                }));

        StepVerifier.create(result).expectNext("cached").verifyComplete();
        assertTrue(!executed.get());
    }

    @Test
    void rejectsTerminationWithoutExplicitResult() {
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
                context.terminateAtCompile("blocked");
                return Mono.empty();
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);

        StepVerifier.create(executor.executeMono(chain, context,
                        executor.prepare(chain, context, Mono::empty),
                        () -> Mono.just("database")))
                .expectErrorMessage("SQL execution was terminated without an explicit result: "
                        + "status=TERMINATED_AT_COMPILE, reason=blocked")
                .verify();
    }

    @Test
    void awaitsAfterExecuteBeforeFluxCompletes() {
        AtomicBoolean afterCompleted = new AtomicBoolean();
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> afterExecuteReactive(SqlExecutionContext context) {
                return Mono.delay(Duration.ofMillis(10))
                        .then(Mono.fromRunnable(() -> afterCompleted.set(true)));
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);

        Flux<String> result = executor.executeFlux(chain, context,
                executor.prepare(chain, context, Mono::empty),
                () -> Flux.just("a", "b"));

        StepVerifier.create(result)
                .expectNext("a", "b")
                .verifyComplete();
        assertTrue(afterCompleted.get());
        assertEquals(2, context.getResultCount());
    }

    @Test
    void recordsAffectedRowsForDataModificationMono() {
        SqlExecutionContext context = context();
        context.setSql("UPDATE users SET enabled = true");
        context.setCommandType(SqlCommandType.UPDATE);
        SqlLifecycleInterceptorChain chain = chain(new ReactiveSqlLifecycleInterceptor() {
        });

        StepVerifier.create(executor.executeMono(chain, context,
                        executor.prepare(chain, context, Mono::empty),
                        () -> Mono.just(3L)))
                .expectNext(3L)
                .verifyComplete();

        assertEquals(3, context.getResultCount());
        assertEquals(3, context.getAffectedRowCount());
        assertEquals(0, context.getReturnedRowCount());
    }

    @Test
    void recordsZeroForEmptyMonoResult() {
        SqlExecutionContext context = context();
        context.setSql("SELECT * FROM users WHERE id = -1");
        SqlLifecycleInterceptorChain chain = chain(new ReactiveSqlLifecycleInterceptor() {
        });

        StepVerifier.create(executor.executeMono(chain, context,
                        executor.prepare(chain, context, Mono::empty),
                        Mono::<String>empty))
                .verifyComplete();

        assertEquals(0, context.getResultCount());
    }

    @Test
    void propagatesBeforeInterceptorErrorWithoutExecutingStatement() {
        IllegalStateException beforeError = new IllegalStateException("before");
        AtomicBoolean executed = new AtomicBoolean();
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
                return Mono.error(beforeError);
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);

        Mono<String> result = executor.executeMono(chain, context,
                executor.prepare(chain, context, Mono::empty),
                () -> Mono.fromSupplier(() -> {
                    executed.set(true);
                    return "database";
                }));

        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertSame(beforeError, error))
                .verify();
        assertTrue(!executed.get());
    }

    @Test
    void executesLifecycleInTheUnifiedOrder() {
        List<String> events = new ArrayList<>();
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
                return Mono.fromRunnable(() -> events.add("beforeCompile"));
            }

            @Override
            public Mono<Void> afterCompileReactive(SqlExecutionContext context) {
                return Mono.fromRunnable(() -> events.add("afterCompile"));
            }

            @Override
            public Mono<Void> beforeExecuteReactive(SqlExecutionContext context) {
                return Mono.fromRunnable(() -> events.add("beforeExecute"));
            }

            @Override
            public Mono<Void> afterExecuteReactive(SqlExecutionContext context) {
                return Mono.fromRunnable(() -> events.add("afterExecute"));
            }
        };
        SqlExecutionContext context = context();
        SqlLifecycleInterceptorChain chain = chain(interceptor);
        Mono<Boolean> preparation = executor.prepare(chain, context,
                () -> Mono.fromRunnable(() -> events.add("compile")));

        StepVerifier.create(executor.executeMono(chain, context, preparation,
                        () -> Mono.fromSupplier(() -> {
                            events.add("statement");
                            return "value";
                        })))
                .expectNext("value")
                .verifyComplete();

        assertEquals(List.of("beforeCompile", "compile", "afterCompile", "beforeExecute",
                "statement", "afterExecute"), events);
    }

    private SqlLifecycleInterceptorChain chain(ReactiveSqlLifecycleInterceptor interceptor) {
        return new SqlLifecycleInterceptorChain(List.of(), List.of(interceptor));
    }

    private SqlExecutionContext context() {
        try {
            Method method = String.class.getMethod("isEmpty");
            return new SqlExecutionContext(method, String.class, new Object[0]);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
