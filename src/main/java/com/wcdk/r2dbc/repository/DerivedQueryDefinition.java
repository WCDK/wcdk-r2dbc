package com.wcdk.r2dbc.repository;

import java.util.List;

/***
 * 启动阶段编译的派生查询定义。
 * @author wcdk
 */
public record DerivedQueryDefinition(String operation, String fieldPart,
                                     List<ConditionDefinition> conditions) {
    public DerivedQueryDefinition {
        conditions = List.copyOf(conditions);
    }

    public record ConditionDefinition(String fieldName, String operator,
                                      String logicalOperator, int argumentCount) {
    }
}