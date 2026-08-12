package com.wcdk.r2dbc.core;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/***
 * Repository 方法计划注册表。
 * @author wcdk
 */
final class RepositoryMethodPlanRegistry {
    private final Map<Method, RepositoryMethodPlan> plans;

    RepositoryMethodPlanRegistry(Map<Method, RepositoryMethodPlan> plans) {
        this.plans = Map.copyOf(Objects.requireNonNull(plans, "plans"));
    }

    RepositoryMethodPlan get(Method method) {
        return plans.get(method);
    }
}