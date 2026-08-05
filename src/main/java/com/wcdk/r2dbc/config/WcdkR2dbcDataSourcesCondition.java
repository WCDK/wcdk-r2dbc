package com.wcdk.r2dbc.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * @auther WCDK
 * @date 2026/7/27
 * @version 1.0
 **/
public class WcdkR2dbcDataSourcesCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, WcdkSpringR2dbcProperties.DataSourceProperties> dataSources = Binder.get(context.getEnvironment())
                .bind("spring.r2dbc.data-sources", Bindable.mapOf(String.class, WcdkSpringR2dbcProperties.DataSourceProperties.class))
                .orElse(Map.of());
        return !dataSources.isEmpty();
    }
}
