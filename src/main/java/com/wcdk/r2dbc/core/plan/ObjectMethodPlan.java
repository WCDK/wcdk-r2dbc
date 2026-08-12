package com.wcdk.r2dbc.core.plan;

/***
 * Object 方法计划。
 * @author wcdk
 */
public final class ObjectMethodPlan implements RepositoryPlan {
    private final RepositoryMethodPlan plan;
    public ObjectMethodPlan(RepositoryMethodPlan plan) { this.plan = plan; }
    public RepositoryMethodPlan legacy() { return plan; }
}