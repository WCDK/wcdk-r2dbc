package com.wcdk.r2dbc.repository.plan;

/***
 * Derived Query 参数绑定计划。
 * @author wcdk
 **/
public record ParameterBindingPlan(int index, String name, Class<?> type) {
}