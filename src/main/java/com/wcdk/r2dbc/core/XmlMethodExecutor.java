package com.wcdk.r2dbc.core;

import java.util.function.BiFunction;
import reactor.util.context.ContextView;

/***
 * XML 方法执行器。
 * @author wcdk
 */
final class XmlMethodExecutor implements RepositoryMethodExecutor {
    private final RepositoryPlanExecutor delegate;

    XmlMethodExecutor(RepositoryPlanExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.XML;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy) {
        return delegate.apply(plan, args, context, proxy);
    }
}
