package com.wcdk.r2dbc.core.executor;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.core.interceptor.ReactiveSqlLifecycleInterceptor;
import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.log.R2dbcSqlLogger;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class R2dbcQueryOperationsTests {

    @Test
    void repeatedAndConcurrentSubscriptionsUseIndependentContexts() {
        List<SqlExecutionContext> contexts = new CopyOnWriteArrayList<>();
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
                contexts.add(context);
                context.cacheHit(Map.of("executionId", context.getExecutionId()));
                return Mono.empty();
            }
        };
        SqlLifecycleInterceptorChain chain =
                new SqlLifecycleInterceptorChain(List.of(), List.of(interceptor));
        R2dbcQueryOperations operations = new R2dbcQueryOperations(
                mock(DatabaseClient.class),
                mock(ParameterBinder.class),
                new SqlLifecycleExecutor(chain),
                new R2dbcSqlLogger(new WcdkR2dbcProperties(), new WcdkSpringR2dbcProperties()));

        Flux<Map<String, Object>> publisher = operations.query("SELECT 1");

        StepVerifier.create(Flux.merge(publisher.collectList(), publisher.collectList()))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0)).isNotSameAs(contexts.get(1));
        assertThat(contexts.get(0).getExecutionId()).isNotEqualTo(contexts.get(1).getExecutionId());
    }

    @Test
    void retryAndRepeatCreateFreshContexts() {
        List<SqlExecutionContext> contexts = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        ReactiveSqlLifecycleInterceptor interceptor = new ReactiveSqlLifecycleInterceptor() {
            @Override
            public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
                contexts.add(context);
                if (attempts.getAndIncrement() == 0) {
                    return Mono.error(new IllegalStateException("retry"));
                }
                context.cacheHit(Map.of("attempt", attempts.get()));
                return Mono.empty();
            }
        };
        SqlLifecycleInterceptorChain chain =
                new SqlLifecycleInterceptorChain(List.of(), List.of(interceptor));
        R2dbcQueryOperations operations = new R2dbcQueryOperations(
                mock(DatabaseClient.class), mock(ParameterBinder.class), new SqlLifecycleExecutor(chain),
                new R2dbcSqlLogger(new WcdkR2dbcProperties(), new WcdkSpringR2dbcProperties()));

        StepVerifier.create(operations.query("SELECT 1").retry(1).repeat(1))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(contexts).hasSize(3);
        assertThat(contexts.stream().map(SqlExecutionContext::getExecutionId).distinct()).hasSize(3);
    }
}
