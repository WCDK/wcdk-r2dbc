package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import org.junit.jupiter.api.Test;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SqlExecutionObserverTests {

    @Test
    void observesStablePhasesWithoutExecutingFluxTwice() {
        RecordingObserver observer = new RecordingObserver();
        SqlLifecycleInterceptorChain chain = new SqlLifecycleInterceptorChain(List.of(), List.of());
        SqlLifecycleExecutor executor = new SqlLifecycleExecutor(chain, observer);
        SqlExecutionContext context = executor.createContext("query", Map.of());
        Mono<Boolean> preparation = executor.prepare(chain, context, Mono::empty);
        AtomicInteger subscriptions = new AtomicInteger();

        Flux<Integer> result = executor.executeFlux(chain, context, preparation,
                () -> Flux.defer(() -> {
                    subscriptions.incrementAndGet();
                    return Flux.just(1, 2);
                }));

        StepVerifier.create(result).expectNext(1, 2).verifyComplete();
        assertThat(subscriptions).hasValue(1);
        assertThat(observer.phases).containsExactly(
                SqlExecutionPhase.PREPARE, SqlExecutionPhase.COMPILE,
                SqlExecutionPhase.REWRITE, SqlExecutionPhase.VALIDATE,
                SqlExecutionPhase.EXECUTE, SqlExecutionPhase.FINALLY);
    }

    @Test
    void micrometerUsesOnlyStableLowCardinalityTags() {
        ObservationRegistry registry = ObservationRegistry.create();
        List<String> tags = new ArrayList<>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStart(Observation.Context context) {
                tags.add(context.getLowCardinalityKeyValues().toString());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        SqlLifecycleExecutor executor = new SqlLifecycleExecutor(
                new SqlLifecycleInterceptorChain(List.of(), List.of()),
                new MicrometerSqlExecutionObserver(registry));
        SqlExecutionContext context = executor.createContext("query", Map.of("password", "secret"));
        context.setSql("SELECT * FROM users WHERE token = 'secret'");

        StepVerifier.create(executor.executeMono(executor.getChain(), context,
                        executor.prepare(executor.getChain(), context, Mono::empty), () -> Mono.just("ok")))
                .expectNext("ok").verifyComplete();

        assertThat(tags).isNotEmpty().allSatisfy(value -> {
            assertThat(value).contains("phase=").contains("command=").contains("statement=")
                    .contains("datasource=")
                    .doesNotContain("SELECT").doesNotContain("secret").doesNotContain("password");
        });
    }

    private static final class RecordingObserver implements SqlExecutionObserver {
        private final List<SqlExecutionPhase> phases = new ArrayList<>();

        @Override
        public <T> Mono<T> observe(SqlExecutionPhase phase, SqlExecutionContext context, Mono<T> action) {
            return Mono.defer(() -> {
                phases.add(phase);
                return action;
            });
        }

        @Override
        public <T> Flux<T> observeFlux(SqlExecutionPhase phase, SqlExecutionContext context, Flux<T> action) {
            return Flux.defer(() -> {
                phases.add(phase);
                return action;
            });
        }
    }
}
