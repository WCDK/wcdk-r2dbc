package com.wcdk.r2dbc.core;

import java.util.function.BiFunction;

/***
 * XML 方法执行器。
 * @author wcdk
 */
final class XmlMethodExecutor implements RepositoryMethodExecutor {
    private final BiFunction<RepositoryMethodPlan, Object[], Object> delegate;

    XmlMethodExecutor(BiFunction<RepositoryMethodPlan, Object[], Object> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.XML;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args) {
        return delegate.apply(plan, args);
    }
}
