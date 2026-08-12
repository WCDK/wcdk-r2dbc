package com.wcdk.r2dbc.core.plan;

/***
 * 不支持方法计划。
 * @author wcdk
 */
public final class UnsupportedMethodPlan implements RepositoryPlan {
    private final RepositoryMethodPlan plan;
    public UnsupportedMethodPlan(RepositoryMethodPlan plan) { this.plan = plan; }
    public RepositoryMethodPlan legacy() { return plan; }
}