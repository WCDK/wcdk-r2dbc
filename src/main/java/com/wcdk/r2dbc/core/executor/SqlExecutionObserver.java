package com.wcdk.r2dbc.core.executor;

import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/**
 * 用于结构化 SQL 执行观测的可选低开销钩子。
 *
 * @author WCDK
 **/
public interface SqlExecutionObserver {

    SqlExecutionObserver NOOP = new SqlExecutionObserver() { };

    default <T> Mono<T> observe(SqlExecutionPhase phase, SqlExecutionContext context, Mono<T> action) {
        return action;
    }

    default <T> Flux<T> observeFlux(SqlExecutionPhase phase, SqlExecutionContext context, Flux<T> action) {
        return action;
    }
}
