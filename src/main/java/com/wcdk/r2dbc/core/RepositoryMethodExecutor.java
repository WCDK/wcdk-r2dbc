package com.wcdk.r2dbc.core;

import reactor.util.context.ContextView;

/***
 * Repository 方法执行器。
 * @author wcdk
 */
@FunctionalInterface
interface RepositoryPlanExecutor {
    Object apply(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy);
}

interface RepositoryMethodExecutor {
    boolean supports(RepositoryMethodPlan plan);
    Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy);
}
