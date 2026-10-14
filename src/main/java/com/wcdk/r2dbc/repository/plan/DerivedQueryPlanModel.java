package com.wcdk.r2dbc.repository.plan;
import com.wcdk.r2dbc.execution.SqlCommandType;

import com.wcdk.r2dbc.repository.DerivedQueryDefinition;
import com.wcdk.r2dbc.repository.CustomMethodResolver;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

/**
 * 派生查询 启动期完整编译模型。
 * @author wcdk
 */
public record DerivedQueryPlanModel(Method method, DerivedQueryDefinition definition, ReturnTypePlan returnType) {
    public SqlCommandType commandType() { return SqlCommandType.fromMethodName(definition.operation()); }

    public static DerivedQueryPlanModel compile(Method method, DerivedQueryDefinition definition,
                                                RepositoryMetadata entity) {
        Class<?> elementType = ResultPlan.of(method, entity.entityClass()).reactiveElementType();
        return new DerivedQueryPlanModel(method, definition,
                new ReturnTypePlan(method.getReturnType(), method.getGenericReturnType(), elementType));
    }
}
