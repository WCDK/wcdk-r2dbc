package com.wcdk.r2dbc.core;

import java.util.function.BiFunction;

/**
 *  CRUD 方法执行器。
 *  @author wcdk`
 *  */
final class CrudMethodExecutor implements RepositoryMethodExecutor {
    private final BiFunction<RepositoryMethodPlan, Object[], Object> delegate;

    CrudMethodExecutor(BiFunction<RepositoryMethodPlan, Object[], Object> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.CRUD
                || plan.kind() == RepositoryMethodPlan.Kind.OBJECT;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args) {
        return delegate.apply(plan, args);
    }
}
