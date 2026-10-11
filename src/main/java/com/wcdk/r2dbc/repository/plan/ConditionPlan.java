package com.wcdk.r2dbc.repository.plan;

/**
 * 派生查询 条件编译计划。
 * @author wcdk
 */
public record ConditionPlan(String fieldName, String column, String operator,
                            String logicalOperator, int argumentCount) {
}