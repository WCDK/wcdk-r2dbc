package com.wcdk.r2dbc.datasource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * @auther WCDK
 * @date 2026/7/27
 * @version 1.0
 **/
public final class R2dbcDataSourceContext {

    static final Class<R2dbcDataSourceContext> CONTEXT_KEY = R2dbcDataSourceContext.class;

    private R2dbcDataSourceContext() {
    }

    public static <T> Mono<T> use(String dataSource, Mono<T> publisher) {
        return publisher.contextWrite(context -> context.put(CONTEXT_KEY, requireDataSource(dataSource)));
    }

    public static <T> Flux<T> use(String dataSource, Flux<T> publisher) {
        return publisher.contextWrite(context -> context.put(CONTEXT_KEY, requireDataSource(dataSource)));
    }

    public static String get(ContextView contextView) {
        return contextView.getOrDefault(CONTEXT_KEY, null);
    }

    static String requireDataSource(String dataSource) {
        if (dataSource == null || dataSource.isBlank()) {
            throw new IllegalArgumentException("R2DBC data source key is blank");
        }
        return dataSource;
    }
}
