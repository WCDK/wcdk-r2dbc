package com.wcdk.r2dbc.core;

import java.util.function.BiFunction;

/***
 * Derived Query 方法执行器。
 * @author wcdk
 */
final class DerivedMethodExecutor implements RepositoryMethodExecutor {
    private final BiFunction<RepositoryMethodPlan, Object[], Object> delegate;

    DerivedMethodExecutor(BiFunction<RepositoryMethodPlan, Object[], Object> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.DERIVED;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args) {
        return delegate.apply(plan, args);
    }
}
