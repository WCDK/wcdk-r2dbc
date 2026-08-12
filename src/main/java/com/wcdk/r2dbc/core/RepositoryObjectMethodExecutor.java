package com.wcdk.r2dbc.core;

/***
 * Repository 对象方法执行器。
 * @author wcdk
 */
final class RepositoryObjectMethodExecutor implements RepositoryMethodExecutor {
    private final Class<?> repositoryInterface;
    private final com.wcdk.r2dbc.core.metadata.RepositoryMetadata metadata;

    RepositoryObjectMethodExecutor(Class<?> repositoryInterface, com.wcdk.r2dbc.core.metadata.RepositoryMetadata metadata) {
        this.repositoryInterface = repositoryInterface;
        this.metadata = metadata;
    }

    Object execute(RepositoryInvocation invocation) {
        return switch (invocation.getMethod().getName()) {
            case "toString" -> "WcdkR2dbcRepositoryProxy(" + (metadata != null ? metadata.entityClass().getName() : repositoryInterface.getSimpleName()) + ")";
            case "hashCode" -> System.identityHashCode(invocation.getThis());
            case "equals" -> invocation.getThis() == invocation.arguments()[0];
            default -> throw new UnsupportedOperationException("不支持的对象方法: " + invocation.getMethod());
        };
    }



    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.OBJECT;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args, reactor.util.context.ContextView context, Object proxy) {
        return execute(new RepositoryInvocation(plan, proxy, args));
    }
}