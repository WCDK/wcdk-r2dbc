package com.wcdk.r2dbc.datasource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

public final class R2dbcDataSourceContext {

    static final Class<R2dbcDataSourceContext> CONTEXT_KEY = R2dbcDataSourceContext.class;
    private static final String TRANSACTION_KEY = R2dbcDataSourceContext.class.getName() + ".transaction";
    private static final String PRIMARY = "<primary>";

    private R2dbcDataSourceContext() {
    }

    public static <T> Mono<T> use(String dataSource, Mono<T> publisher) {
        String key = requireDataSource(dataSource);
        return Mono.deferContextual(context -> {
                    assertTransactionDataSource(context, key);
                    return publisher;
                })
                .contextWrite(context -> context.put(CONTEXT_KEY, key));
    }

    public static <T> Flux<T> use(String dataSource, Flux<T> publisher) {
        String key = requireDataSource(dataSource);
        return Flux.deferContextual(context -> {
                    assertTransactionDataSource(context, key);
                    return publisher;
                })
                .contextWrite(context -> context.put(CONTEXT_KEY, key));
    }

    public static <T> Mono<T> pinTransactionDataSource(Mono<T> publisher) {
        return Mono.deferContextual(context -> {
            String dataSource = get(context);
            String pinned = dataSource == null ? PRIMARY : dataSource;
            return publisher.contextWrite(current -> current.put(TRANSACTION_KEY, pinned));
        });
    }

    public static <T> Flux<T> pinTransactionDataSource(Flux<T> publisher) {
        return Flux.deferContextual(context -> {
            String dataSource = get(context);
            String pinned = dataSource == null ? PRIMARY : dataSource;
            return publisher.contextWrite(current -> current.put(TRANSACTION_KEY, pinned));
        });
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

    private static void assertTransactionDataSource(ContextView context, String requested) {
        String pinned = context.getOrDefault(TRANSACTION_KEY, null);
        if (pinned != null && !pinned.equals(requested)) {
            throw new IllegalStateException("Cannot switch R2DBC data source from "
                    + pinned + " to " + requested + " after a transaction has started");
        }
    }
}