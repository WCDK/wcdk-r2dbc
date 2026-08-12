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

    RepositoryPlan get(Method method) {
        RepositoryMethodPlan plan = plans.get(method);
        if (plan == null) return null;
        return switch (plan.kind()) {
            case OBJECT -> new ObjectMethodPlan(plan);
            case CRUD -> new CrudMethodPlan(plan);
            case DERIVED -> new DerivedQueryPlan(plan);
            case XML -> new XmlStatementPlan(plan);
            case UNSUPPORTED -> new UnsupportedMethodPlan(plan);
        };
    }
}