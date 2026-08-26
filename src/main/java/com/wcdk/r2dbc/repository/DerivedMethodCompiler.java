package com.wcdk.r2dbc.repository;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import com.wcdk.r2dbc.repository.plan.ResultPlan;
import com.wcdk.r2dbc.repository.plan.DerivedQueryPlanModel;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 派生仓库方法分发的启动校验与编译器。
 *
 * @author wcdk
 **/
public final class DerivedMethodCompiler {
    private final Class<?> repositoryInterface;
    private final CustomMethodResolver resolver;
    private final RepositoryMetadata metadata;

    public DerivedMethodCompiler(Class<?> repositoryInterface, RepositoryMetadata metadata,
                                 WcdkR2dbcProperties properties) {
        this.repositoryInterface = repositoryInterface;
        this.metadata = metadata;
        this.resolver = metadata == null ? null : new CustomMethodResolver(metadata,
                properties.getLogicDeleteValue(), properties.getLogicNotDeleteValue());
    }

    public Optional<RepositoryMethodPlan> compile(Method method) {
        if (resolver == null || !CustomMethodResolver.supports(method)) {
            return Optional.empty();
        }
        try {
            resolver.validateMethod(method);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无效的派生仓库方法: "
                    + method.toGenericString(), error);
        }
        DerivedQueryDefinition derivedPlan = resolver.compile(method);
        String statementId = repositoryInterface.getName() + "." + method.getName();
        return Optional.of(new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.DERIVED, null, statementId,
                new RepositoryMethodPlan.StatementDefinition(RepositoryMethodPlan.Kind.DERIVED, statementId),
                new RepositoryMethodPlan.SqlPlan(null, false, DerivedQueryPlanModel.compile(method, derivedPlan, metadata)),
                RepositoryMethodPlan.ParameterPlan.of(method),
                ResultPlan.of(method, null)));
    }

}
