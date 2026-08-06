package com.wcdk.r2dbc.core;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryProxyMethodInterceptorTests {

    @Test
    void usesDeclaredReactiveTypeForTerminatedInvocation() throws Exception {
        Object mono = RepositoryProxyMethodInterceptor.terminatedPublisher(
                ReturnTypes.class.getDeclaredMethod("mono"));
        Object flux = RepositoryProxyMethodInterceptor.terminatedPublisher(
                ReturnTypes.class.getDeclaredMethod("flux"));

        assertThat(mono).isInstanceOf(Mono.class);
        assertThat(flux).isInstanceOf(Flux.class);
    }

    private interface ReturnTypes {
        Mono<String> mono();

        Flux<String> flux();
    }
}
