package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * Micrometer 观测适配器，明确不会为原始 SQL 或参数添加标签。
 *
 * @author WCDK
 **/
public final class MicrometerSqlExecutionObserver implements SqlExecutionObserver {

    private final ObservationRegistry registry;

    public MicrometerSqlExecutionObserver(ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public <T> Mono<T> observe(SqlExecutionPhase phase, SqlExecutionContext context, Mono<T> action) {
        return Mono.deferContextual(contextView -> {
            String dataSource = R2dbcDataSourceContext.get(contextView);
            Observation observation = observation(phase, context, dataSource);
            return action.doOnError(observation::error).doFinally(ignored -> observation.stop());
        });
    }

    @Override
    public <T> Flux<T> observeFlux(SqlExecutionPhase phase, SqlExecutionContext context, Flux<T> action) {
        return Flux.deferContextual(contextView -> {
            Observation observation = observation(phase, context,
                    R2dbcDataSourceContext.get(contextView));
            return action.doOnError(observation::error).doFinally(ignored -> observation.stop());
        });
    }

    private Observation observation(SqlExecutionPhase phase, SqlExecutionContext context, String dataSource) {
        return Observation.createNotStarted("wcdk.r2dbc.sql.phase", registry)
                .lowCardinalityKeyValue("phase", phase.name().toLowerCase(java.util.Locale.ROOT))
                .lowCardinalityKeyValue("command", context.getCommandType().name().toLowerCase(java.util.Locale.ROOT))
                .lowCardinalityKeyValue("statement", context.getStatementId())
                .lowCardinalityKeyValue("datasource", dataSource == null ? "primary" : dataSource)
                .start();
    }
}
