package com.wcdk.r2dbc.core.plan;

import java.lang.reflect.Method;

/***
 * Repository 方法计划。
 * @author wcdk
 */
public sealed interface RepositoryPlan permits ObjectMethodPlan, CrudQueryPlan, DerivedQueryPlan, XmlQueryPlan, UnsupportedMethodPlan {
    RepositoryMethodPlan legacy();
    default Method method() { return legacy().method(); }
    default RepositoryMethodPlan.Kind kind() { return legacy().kind(); }
}