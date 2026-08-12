package com.wcdk.r2dbc.core.plan;

/***
 * Derived Query 计划。
 * @author wcdk
 */
public final class DerivedQueryPlan implements RepositoryPlan {
    private final RepositoryMethodPlan plan;
    public DerivedQueryPlan(RepositoryMethodPlan plan) { this.plan = plan; }
    public RepositoryMethodPlan legacy() { return plan; }
}