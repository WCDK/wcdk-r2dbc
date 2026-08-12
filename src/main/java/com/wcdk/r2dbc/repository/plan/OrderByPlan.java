package com.wcdk.r2dbc.repository.plan;

/***
 * Derived Query 排序编译计划。
 * @author wcdk
 **/
public record OrderByPlan(String fieldName, String column, boolean ascending) {
}