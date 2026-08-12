package com.wcdk.r2dbc.core;

import java.util.function.BiFunction;
import reactor.util.context.ContextView;

/***
 * Derived Query 方法执行器。
 * @author wcdk
 */
final class DerivedMethodExecutor implements RepositoryMethodExecutor {
    private final RepositoryPlanExecutor delegate;

    DerivedMethodExecutor(RepositoryPlanExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.DERIVED;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy) {
        return delegate.apply(plan, args, context, proxy);
    }
}
