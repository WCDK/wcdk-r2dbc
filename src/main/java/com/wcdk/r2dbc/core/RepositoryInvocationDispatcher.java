package com.wcdk.r2dbc.core;

import java.util.List;
import reactor.util.context.ContextView;

/***
 * Repository 方法执行分发器。
 * @author wcdk
 */
final class RepositoryInvocationDispatcher {
    private final List<RepositoryMethodExecutor> executors;

    RepositoryInvocationDispatcher(List<RepositoryMethodExecutor> executors) {
        this.executors = List.copyOf(executors);
    }

    Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context) {
        return executors.stream()
                .filter(executor -> executor.supports(plan))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "没有执行器支持该Repository方法: " + plan.method().toGenericString()))
                .execute(plan, args, context);
    }
}
