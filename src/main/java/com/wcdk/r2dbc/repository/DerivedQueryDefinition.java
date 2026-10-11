package com.wcdk.r2dbc.repository;

import java.util.List;

/**
 * 启动阶段编译的派生查询定义。
 * @author wcdk
 */
public record DerivedQueryDefinition(String operation, String fieldPart,
                                     List<ConditionDefinition> conditions, List<String> setFields, List<OrderDefinition> orders) {
    public DerivedQueryDefinition(String operation, String fieldPart, List<ConditionDefinition> conditions) {
        this(operation, fieldPart, conditions, List.of(), List.of());
    }
    public DerivedQueryDefinition {
        conditions = List.copyOf(conditions);
        setFields = List.copyOf(setFields);
        orders = List.copyOf(orders);
    }

    public record OrderDefinition(String fieldName, boolean ascending) {}

    public record ConditionDefinition(String fieldName, String operator,
                                      String logicalOperator, int argumentCount) {
    }
}