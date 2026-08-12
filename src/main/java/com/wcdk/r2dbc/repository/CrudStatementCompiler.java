package com.wcdk.r2dbc.repository;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;

import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 用于 Object 和基础 CRUD 方法稳定分发的启动编译器。
 *
 * @author WCDK
 **/
public final class CrudStatementCompiler {
    private final Class<?> repositoryInterface;
    private final RepositoryMetadata metadata;

    public CrudStatementCompiler(Class<?> repositoryInterface, RepositoryMetadata metadata) {
        this.repositoryInterface = repositoryInterface;
        this.metadata = metadata;
    }

    public Optional<RepositoryMethodPlan> compile(Method method) {
        String id = repositoryInterface.getName() + "." + method.getName();
        if (RepositoryMethodPlanCompiler.isObjectMethod(method.getName())) {
            return Optional.of(new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.OBJECT, null, id));
        }
        if (!RepositoryMethodPlanCompiler.isCrudMethod(method)) {
            return Optional.empty();
        }
        if (metadata == null) {
            throw new IllegalArgumentException("基础CRUD方法需要BaseRepository实体元数据: "
                    + method.toGenericString());
        }
        return Optional.of(new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.CRUD, null, id));
    }
}
