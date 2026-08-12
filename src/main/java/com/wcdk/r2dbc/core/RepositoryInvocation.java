package com.wcdk.r2dbc.core;
import com.wcdk.r2dbc.core.plan.RepositoryMethodPlan;

import java.lang.reflect.Method;

/**
 * 单次订阅范围内的调用状态。
 *
 * @author WCDK
 **/
public record RepositoryInvocation(RepositoryMethodPlan plan, Object proxy, Object[] arguments) {
    public RepositoryInvocation {
        java.util.Objects.requireNonNull(plan, "plan");
        arguments = arguments == null ? new Object[0] : arguments.clone();
    }

    @Override
    public Object[] arguments() {
        return arguments.clone();
    }

    Method getMethod() {
        return plan.method();
    }

    Object getThis() {
        return proxy;
    }
}
