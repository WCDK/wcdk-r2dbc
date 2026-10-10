package com.wcdk.r2dbc.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Avoids two fallback factories when single and multi-source properties coexist. */
final class WcdkR2dbcSingleDataSourceCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !new WcdkR2dbcDataSourcesCondition().matches(context, metadata);
    }
}
