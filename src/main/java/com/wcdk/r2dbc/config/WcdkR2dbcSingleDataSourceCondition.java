package com.wcdk.r2dbc.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 单数据源与多数据源配置同时存在时，避免创建两个回退连接工厂。 */
final class WcdkR2dbcSingleDataSourceCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !new WcdkR2dbcDataSourcesCondition().matches(context, metadata);
    }
}
