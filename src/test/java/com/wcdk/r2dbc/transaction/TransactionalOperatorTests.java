package com.wcdk.r2dbc.transaction;

import com.wcdk.r2dbc.execution.R2dbcTransactionOperations;

import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/***
 * 验证 TransactionalOperator 的响应式事务生命周期。
 * @author wcdk
 **/
class TransactionalOperatorTests {

    /***
     * 验证成功事务按 begin、SQL、commit 顺序执行。
     * @author wcdk
     */
    @Test
    void commitsAfterSqlCompletes() {
        List<String> events = new ArrayList<>();
        TransactionalOperator operator = operator(events);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        R2dbcTransactionOperations operations = new R2dbcTransactionOperations(databaseClient, operator);

        StepVerifier.create(operations.transaction(client ->
                        Flux.defer(() -> {
                            events.add("SQL");
                            return Flux.just("ok");
                        })))
                .expectNext("ok")
                .verifyComplete();

        assertThat(events).containsExactly("begin", "SQL", "commit");
    }

    /***
     * 验证 SQL 失败时按 begin、SQL、rollback 顺序执行。
     * @author wcdk
     */
    @Test
    void rollsBackWhenSqlFails() {
        List<String> events = new ArrayList<>();
        TransactionalOperator operator = operator(events);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        R2dbcTransactionOperations operations = new R2dbcTransactionOperations(databaseClient, operator);
        IllegalStateException failure = new IllegalStateException("SQL执行失败");

        StepVerifier.create(operations.transaction(client ->
                        Flux.defer(() -> {
                            events.add("SQL");
                            return Flux.error(failure);
                        })))
                .expectErrorMatches(error -> error == failure)
                .verify();

        assertThat(events).containsExactly("begin", "SQL", "rollback");
    }

    /***
     * 创建记录事务生命周期的 Spring 响应式事务操作器。
     * @author wcdk
     */
    private TransactionalOperator operator(List<String> events) {
        ReactiveTransaction transaction = mock(ReactiveTransaction.class);
        ReactiveTransactionManager manager = new ReactiveTransactionManager() {
            @Override
            public Mono<ReactiveTransaction> getReactiveTransaction(TransactionDefinition definition) {
                events.add("begin");
                return Mono.just(transaction);
            }

            @Override
            public Mono<Void> commit(ReactiveTransaction transaction) {
                events.add("commit");
                return Mono.empty();
            }

            @Override
            public Mono<Void> rollback(ReactiveTransaction transaction) {
                events.add("rollback");
                return Mono.empty();
            }
        };
        return TransactionalOperator.create(manager);
    }
}