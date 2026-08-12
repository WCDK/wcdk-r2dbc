package com.wcdk.r2dbc.core.plan;

/***
 * CRUD 查询计划。
 * @author wcdk
 */
public final class CrudQueryPlan implements RepositoryPlan {
    private final RepositoryMethodPlan plan;
    public CrudQueryPlan(RepositoryMethodPlan plan) { this.plan = plan; }
    public RepositoryMethodPlan legacy() { return plan; }
}