package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;

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

    /***
     * 查找 Repository 方法的启动期编译计划。
     *
     * @param method Repository 方法
     * @return 编译计划，未找到时返回 {@code null}
     * @author wcdk
     **/
    RepositoryMethodPlan get(Method method) {
        return plans.get(method);
    }
}