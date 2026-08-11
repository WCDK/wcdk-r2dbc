package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 派生仓库方法分发的启动校验与编译器。
 *
 * @author WCDK
 **/
public final class DerivedMethodCompiler {
    private final Class<?> repositoryInterface;
    private final CustomMethodResolver resolver;

    public DerivedMethodCompiler(Class<?> repositoryInterface, RepositoryMetadata metadata,
                                 WcdkR2dbcProperties properties) {
        this.repositoryInterface = repositoryInterface;
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
            throw new IllegalArgumentException("Invalid derived repository method: "
                    + method.toGenericString(), error);
        }
        return Optional.of(new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.DERIVED, null,
                repositoryInterface.getName() + "." + method.getName()));
    }
}
