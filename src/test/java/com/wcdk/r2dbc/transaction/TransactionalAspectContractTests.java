package com.wcdk.r2dbc.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionalAspectContractTests {
    @Test
    void rejectsSynchronousReturnBeforeBusinessMethodOrTransactionStarts() {
        Fixture fixture = new Fixture();
        assertThatThrownBy(fixture.proxy::sync).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须返回Publisher");
        assertThat(fixture.target.calls).hasValue(0);
        verifyNoInteractions(fixture.manager);
    }

    @Test
    void monoIsLazyAndCommitsAfterSubscription() {
        Fixture fixture = new Fixture();
        Mono<String> result = fixture.proxy.mono();
        assertThat(fixture.target.calls).hasValue(0);
        verifyNoInteractions(fixture.manager);
        StepVerifier.create(result).expectNext("one").verifyComplete();
        assertThat(fixture.target.calls).hasValue(1);
        verify(fixture.manager).commit(fixture.transaction);
        verify(fixture.manager, never()).rollback(any());
    }

    @Test
    void fluxPreservesAllElementsAndCommits() {
        Fixture fixture = new Fixture();
        StepVerifier.create(fixture.proxy.flux()).expectNext("one", "two").verifyComplete();
        assertThat(fixture.target.calls).hasValue(1);
        verify(fixture.manager).commit(fixture.transaction);
        verify(fixture.manager, never()).rollback(any());
    }

    private static final class Fixture {
        final ReactiveTransactionManager manager = mock(ReactiveTransactionManager.class);
        final ReactiveTransaction transaction = mock(ReactiveTransaction.class);
        final Service target = new Service();
        final Service proxy;
        Fixture() {
            when(manager.getReactiveTransaction(any(TransactionDefinition.class))).thenReturn(Mono.just(transaction));
            when(manager.commit(transaction)).thenReturn(Mono.empty());
            when(manager.rollback(transaction)).thenReturn(Mono.empty());
            var factory = new AspectJProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAspect(new TransactionalAspect(manager));
            proxy = factory.getProxy();
            clearInvocations(manager);
        }
    }

    @Transactional
    public static class Service {
        final AtomicInteger calls = new AtomicInteger();
        public String sync() { calls.incrementAndGet(); return "sync"; }
        public Mono<String> mono() { calls.incrementAndGet(); return Mono.just("one"); }
        public Flux<String> flux() { calls.incrementAndGet(); return Flux.just("one", "two"); }
    }
}
