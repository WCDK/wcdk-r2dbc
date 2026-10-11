package com.wcdk.r2dbc.repository.plan;

import java.lang.reflect.Type;

/**
 * 派生查询 返回类型计划。
 * @author wcdk
 */
public record ReturnTypePlan(Class<?> returnType, Type genericReturnType, Class<?> elementType) {
}