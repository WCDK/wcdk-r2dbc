package com.wcdk.r2dbc.core;
import com.wcdk.r2dbc.core.plan.RepositoryMethodPlan;

/***
 * 不支持 Repository 方法执行器。
 * @author wcdk
 */
final class UnsupportedMethodExecutor implements RepositoryMethodExecutor {
    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.UNSUPPORTED;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args, reactor.util.context.ContextView context, Object proxy) {
        throw new UnsupportedOperationException("不支持的 Repository 方法: " + plan.method().toGenericString());
    }
}