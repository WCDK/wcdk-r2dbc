package com.wcdk.r2dbc.core;
import com.wcdk.r2dbc.core.plan.ObjectMethodPlan;
import com.wcdk.r2dbc.core.plan.CrudQueryPlan;
import com.wcdk.r2dbc.core.plan.DerivedQueryPlan;
import com.wcdk.r2dbc.core.plan.XmlQueryPlan;
import com.wcdk.r2dbc.core.plan.UnsupportedMethodPlan;
import com.wcdk.r2dbc.core.plan.RepositoryPlan;
import com.wcdk.r2dbc.core.plan.RepositoryMethodPlan;

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
            case CRUD -> new CrudQueryPlan(plan);
            case DERIVED -> new DerivedQueryPlan(plan);
            case XML -> new XmlQueryPlan(plan);
            case UNSUPPORTED -> new UnsupportedMethodPlan(plan);
        };
    }
}