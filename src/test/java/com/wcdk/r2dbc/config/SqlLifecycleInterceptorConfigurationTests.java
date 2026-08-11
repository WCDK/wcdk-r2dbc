package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.core.interceptor.ReactiveSqlLifecycleInterceptor;
import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptor;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLifecycleInterceptorConfigurationTests {

    private static final List<String> EVENTS = new ArrayList<>();

    @Test
    void applicationContextsOwnIndependentReactiveInterceptorChains() {
        try (AnnotationConfigApplicationContext first = context(FirstReactive.class);
             AnnotationConfigApplicationContext second = context(SecondReactive.class)) {
            SqlLifecycleInterceptorChain firstChain = chain(first);
            SqlLifecycleInterceptorChain secondChain = chain(second);

            firstChain.beforeCompileReactive(executionContext()).block();
            secondChain.beforeCompileReactive(executionContext()).block();

            assertThat(EVENTS).containsExactly("first", "second");
            assertThat(firstChain).isNotSameAs(secondChain);
        } finally {
            EVENTS.clear();
        }
    }

    @Test
    void discoversBothKindsAndKeepsTheirOrderedStreamsStable() {
        try (AnnotationConfigApplicationContext context = context(
                ReactiveSecond.class, ReactiveFirst.class, SyncSecond.class, SyncFirst.class)) {
            chain(context).beforeCompileReactive(executionContext()).block();
            assertThat(EVENTS).containsExactly("reactive-1", "reactive-2", "sync-1", "sync-2");
        } finally {
            EVENTS.clear();
        }
    }

    private SqlLifecycleInterceptorChain chain(AnnotationConfigApplicationContext context) {
        return new WcdkR2dbcAutoConfiguration().sqlLifecycleInterceptorChain(
                context.getBeanProvider(SqlLifecycleInterceptor.class),
                context.getBeanProvider(ReactiveSqlLifecycleInterceptor.class));
    }

    @SafeVarargs
    private AnnotationConfigApplicationContext context(
            Class<? extends Object>... interceptorTypes) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        for (Class<?> interceptorType : interceptorTypes) {
            context.registerBean(interceptorType);
        }
        context.refresh();
        return context;
    }

    private SqlExecutionContext executionContext() {
        try {
            Method method = String.class.getMethod("isEmpty");
            return new SqlExecutionContext(method, String.class, new Object[0]);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    static class FirstReactive implements ReactiveSqlLifecycleInterceptor {
        @Override
        public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
            return Mono.fromRunnable(() -> EVENTS.add("first"));
        }
    }

    static class SecondReactive implements ReactiveSqlLifecycleInterceptor {
        @Override
        public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
            return Mono.fromRunnable(() -> EVENTS.add("second"));
        }
    }

    @Order(1)
    static class ReactiveFirst implements ReactiveSqlLifecycleInterceptor {
        @Override
        public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
            return Mono.fromRunnable(() -> EVENTS.add("reactive-1"));
        }
    }

    @Order(2)
    static class ReactiveSecond implements ReactiveSqlLifecycleInterceptor {
        @Override
        public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
            return Mono.fromRunnable(() -> EVENTS.add("reactive-2"));
        }
    }

    @Order(1)
    static class SyncFirst implements SqlLifecycleInterceptor {
        @Override
        public void beforeCompile(SqlExecutionContext context) {
            EVENTS.add("sync-1");
        }
    }

    @Order(2)
    static class SyncSecond implements SqlLifecycleInterceptor {
        @Override
        public void beforeCompile(SqlExecutionContext context) {
            EVENTS.add("sync-2");
        }
    }
}
