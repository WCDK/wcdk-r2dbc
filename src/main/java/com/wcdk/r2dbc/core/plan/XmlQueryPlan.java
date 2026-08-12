package com.wcdk.r2dbc.core.plan;

/***
 * XML 查询计划。
 * @author wcdk
 */
public final class XmlQueryPlan implements RepositoryPlan {
    private final RepositoryMethodPlan plan;
    public XmlQueryPlan(RepositoryMethodPlan plan) { this.plan = plan; }
    public RepositoryMethodPlan legacy() { return plan; }
}