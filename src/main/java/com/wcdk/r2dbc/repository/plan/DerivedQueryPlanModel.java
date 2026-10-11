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
public record DerivedQueryPlanModel(
        Method method,
        DerivedQueryDefinition definition,
        SqlCommandType commandType,
        RepositoryMetadata entity,
        List<ConditionPlan> conditions,
        List<OrderByPlan> orders,
        List<ParameterBindingPlan> parameters,
        ReturnTypePlan returnType) {

    public DerivedQueryPlanModel {
        conditions = List.copyOf(conditions);
        orders = List.copyOf(orders);
        parameters = List.copyOf(parameters);
    }

    /**
     * 创建预编译的 派生查询 模型。
     *
     * @param method Repository 方法
     * @param definition 方法结构定义
     * @param entity 实体元数据
     * @return 完整编译模型
     * @author wcdk
     */
    public static DerivedQueryPlanModel compile(Method method, DerivedQueryDefinition definition,
                                                RepositoryMetadata entity) {
        List<ConditionPlan> conditions = definition.conditions().stream()
                .map(condition -> new ConditionPlan(
                        condition.fieldName(), entity.columnByName(condition.fieldName()).name(),
                        condition.operator(), condition.logicalOperator(), condition.argumentCount()))
                .toList();
        List<OrderByPlan> orders = definition.orders().stream().map(order -> new OrderByPlan(order.fieldName(),
                entity.columnByName(order.fieldName()).name(), order.ascending())).toList();
        List<ParameterBindingPlan> parameters = java.util.stream.IntStream.range(0, method.getParameterCount())
                .mapToObj(index -> new ParameterBindingPlan(index, method.getParameters()[index].getName(),
                        method.getParameterTypes()[index]))
                .toList();
        Class<?> elementType = com.wcdk.r2dbc.repository.plan.ResultPlan
                .of(method, entity.entityClass()).reactiveElementType();
        return new DerivedQueryPlanModel(method, definition, SqlCommandType.fromMethodName(definition.operation()), entity,
                conditions, orders, parameters,
                new ReturnTypePlan(method.getReturnType(), method.getGenericReturnType(), elementType));
    }

}
