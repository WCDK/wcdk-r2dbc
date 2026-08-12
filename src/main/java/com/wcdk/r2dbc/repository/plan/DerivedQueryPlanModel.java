package com.wcdk.r2dbc.repository.plan;

import com.wcdk.r2dbc.repository.DerivedQueryDefinition;
import com.wcdk.r2dbc.repository.CustomMethodResolver;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

/***
 * Derived Query 启动期完整编译模型。
 * @author wcdk
 **/
public record DerivedQueryPlanModel(
        Method method,
        DerivedQueryDefinition definition,
        QueryCommandType commandType,
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

    /***
     * 创建预编译的 Derived Query 模型。
     *
     * @param method Repository 方法
     * @param definition 方法结构定义
     * @param entity 实体元数据
     * @return 完整编译模型
     * @author wcdk
     **/
    public static DerivedQueryPlanModel compile(Method method, DerivedQueryDefinition definition,
                                                RepositoryMetadata entity) {
        List<ConditionPlan> conditions = definition.conditions().stream()
                .map(condition -> new ConditionPlan(
                        condition.fieldName(), entity.columnByName(decapitalize(condition.fieldName())).name(),
                        condition.operator(), condition.logicalOperator(), condition.argumentCount()))
                .toList();
        List<OrderByPlan> orders = compileOrders(definition.fieldPart(), entity);
        List<ParameterBindingPlan> parameters = java.util.stream.IntStream.range(0, method.getParameterCount())
                .mapToObj(index -> new ParameterBindingPlan(index, method.getParameters()[index].getName(),
                        method.getParameterTypes()[index]))
                .toList();
        Class<?> elementType = com.wcdk.r2dbc.repository.plan.ResultPlan
                .of(method, entity.entityClass()).reactiveElementType();
        return new DerivedQueryPlanModel(method, definition, commandType(definition.operation()), entity,
                conditions, orders, parameters,
                new ReturnTypePlan(method.getReturnType(), method.getGenericReturnType(), elementType));
    }

    private static List<OrderByPlan> compileOrders(String fieldPart, RepositoryMetadata entity) {
        if (fieldPart == null || !fieldPart.contains("OrderBy")) {
            return List.of();
        }
        String orderPart = fieldPart.substring(fieldPart.indexOf("OrderBy") + "OrderBy".length());
        List<OrderByPlan> result = new java.util.ArrayList<>();
        for (String token : orderPart.split("And")) {
            boolean ascending = !token.endsWith("Desc");
            String field = token.endsWith("Asc") || token.endsWith("Desc")
                    ? token.substring(0, token.length() - (ascending ? 3 : 4)) : token;
            result.add(new OrderByPlan(field, entity.columnByName(decapitalize(field)).name(), ascending));
        }
        return result;
    }

    private static String decapitalize(String value) {
        return value == null || value.isEmpty() ? value
                : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
    private static QueryCommandType commandType(String operation) {
        return switch (operation) {
            case "find", "count", "exists" -> QueryCommandType.SELECT;
            case "delete" -> QueryCommandType.DELETE;
            case "update" -> QueryCommandType.UPDATE;
            default -> throw new IllegalArgumentException("不支持的 Derived Query 操作：" + operation);
        };
    }
}
