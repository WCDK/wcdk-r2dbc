package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.execution.lifecycle.*;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import com.wcdk.r2dbc.config.*;
import com.wcdk.r2dbc.repository.BaseRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SecurityLifecycleTests {
    private SqlExecutionContext context() throws Exception {
        return new SqlExecutionContext(BaseRepository.class.getMethod("selectById",Object.class),BaseRepository.class,new Object[]{1L});
    }
    @Test void allFinallyCallbacksRunAndSuccessIsPreserved() throws Exception {
        var events=new ArrayList<String>();var context=context();
        SqlLifecycleInterceptor syncBad=new SqlLifecycleInterceptor(){public void afterExecute(SqlExecutionContext c){events.add("syncBad");throw new IllegalStateException("secret");}};
        SqlLifecycleInterceptor syncGood=new SqlLifecycleInterceptor(){public void afterExecute(SqlExecutionContext c){events.add("syncGood");}};
        ReactiveSqlLifecycleInterceptor reactiveBad=new ReactiveSqlLifecycleInterceptor(){public Mono<Void> afterExecuteReactive(SqlExecutionContext c){events.add("reactiveBad");throw new IllegalStateException("secret");}};
        ReactiveSqlLifecycleInterceptor reactiveGood=new ReactiveSqlLifecycleInterceptor(){public Mono<Void> afterExecuteReactive(SqlExecutionContext c){return Mono.fromRunnable(()->events.add("reactiveGood"));}};
        var chain=new SqlLifecycleInterceptorChain(List.of(syncBad,syncGood),List.of(reactiveBad,reactiveGood));var executor=new SqlLifecycleExecutor(chain);
        StepVerifier.create(executor.executeMono(chain,context,executor.prepare(chain,context,Mono::empty),()->Mono.just("success"))).expectNext("success").verifyComplete();
        assertThat(events).containsExactly("reactiveBad","reactiveGood","syncBad","syncGood");
    }
    @Test void businessErrorRetainsIdentityAndAllFinallyFailuresAreSuppressed() throws Exception {
        var business=new IllegalStateException("business");var after=new IllegalStateException("after");var context=context();
        var calls=new AtomicInteger();
        var chain=new SqlLifecycleInterceptorChain(List.of(new SqlLifecycleInterceptor(){public void afterExecute(SqlExecutionContext c){throw after;}},
                new SqlLifecycleInterceptor(){public void afterExecute(SqlExecutionContext c){calls.incrementAndGet();}}),List.of());
        var executor=new SqlLifecycleExecutor(chain);
        StepVerifier.create(executor.executeMono(chain,context,executor.prepare(chain,context,Mono::empty),()->Mono.error(business)))
                .expectErrorSatisfies(error->{assertThat(error).isSameAs(business);assertThat(error.getSuppressed()).containsExactly(after);}).verify();
        assertThat(calls).hasValue(1);
    }
    @Test void skippedAndDegradedExecutionsAreFinalizedOnceWithoutRunningSql() throws Exception {
        for (String mode:List.of("deny","cache","degrade","missingFallback","skip")) {
            var context=context();var finallyCalls=new AtomicInteger();var statements=new AtomicInteger();
            var chain=new SqlLifecycleInterceptorChain(List.of(new SqlLifecycleInterceptor(){
                public void beforeCompile(SqlExecutionContext c){
                    switch(mode){case "deny"->c.denyByPermission("blocked");case "cache"->c.cacheHit("cached");
                        case "degrade"->c.degrade("offline","fallback");case "skip"->c.skipByAudit("skip");default->c.degrade("offline");}
                }
                public void afterExecute(SqlExecutionContext c){finallyCalls.incrementAndGet();assertThat(c.getTerminationType()).isEqualTo(SqlTerminationType.SKIPPED);assertThat(c.getDuration()).isPositive();}
            }),List.of());var executor=new SqlLifecycleExecutor(chain);
            var result=executor.executeMono(chain,context,executor.prepare(chain,context,Mono::empty),()->{statements.incrementAndGet();return Mono.just("sql");});
            if(mode.equals("cache"))StepVerifier.create(result).expectNext("cached").verifyComplete();
            else if(mode.equals("degrade"))StepVerifier.create(result).expectNext("fallback").verifyComplete();
            else StepVerifier.create(result).expectErrorMatches(error->error.getMessage().contains(mode.equals("missingFallback")?"offline":mode.equals("deny")?"blocked":"skip")).verify();
            assertThat(finallyCalls).hasValue(1);assertThat(statements).hasValue(0);
        }
    }
    @Test void beforeFailureAndFluxCacheHitStillReachFinally() throws Exception {
        var context=context();var calls=new AtomicInteger();
        var chain=new SqlLifecycleInterceptorChain(List.of(new SqlLifecycleInterceptor(){
            public void beforeCompile(SqlExecutionContext c){throw new IllegalStateException("before");}
            public void afterExecute(SqlExecutionContext c){calls.incrementAndGet();}
        }),List.of());var executor=new SqlLifecycleExecutor(chain);
        StepVerifier.create(executor.executeFlux(chain,context,executor.prepare(chain,context,Mono::empty),()->Flux.just(1))).expectErrorMessage("before").verify();
        assertThat(calls).hasValue(1);assertThat(context.getTerminationType()).isEqualTo(SqlTerminationType.ERROR);
        var cached=context();cached.cacheHit(List.of(1,2));
        StepVerifier.create(executor.executeFlux(chain,cached,Mono.just(true),()->Flux.just(3))).expectNext(1,2).verifyComplete();
        assertThat(calls).hasValue(2);assertThat(cached.getTerminationType()).isEqualTo(SqlTerminationType.SKIPPED);
    }
    @Test void executionLogUsesTheActualLifecycleId(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var properties=new WcdkR2dbcProperties();properties.setSqlLogEnabled(true);
        var logger=new R2dbcSqlLogger(properties,new WcdkSpringR2dbcProperties());var context=context();
        var chain=new SqlLifecycleInterceptorChain(List.of(),List.of());var executor=new SqlLifecycleExecutor(chain);
        StepVerifier.create(executor.executeMono(chain,context,Mono.just(false),()->Mono.deferContextual(values->{
            logger.logExecution(values,"SELECT :id",Map.of("id",1),1L);return Mono.just(1);
        }))).expectNext(1).verifyComplete();
        assertThat(output.getAll()).contains("executionId="+context.getExecutionId());
    }
}
