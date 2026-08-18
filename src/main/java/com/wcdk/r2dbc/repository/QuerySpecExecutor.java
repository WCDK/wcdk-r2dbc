package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import reactor.util.context.ContextView;

/***
 * Repository 查询规格执行器。
 *
 * @author wcdk
 **/
final class QuerySpecExecutor implements RepositoryMethodExecutor {

    private final CrudRepositoryExecutor crudExecutor;

    /***
     * 创建查询规格执行器。
     *
     * @param crudExecutor CRUD 执行器
     * @author wcdk
     **/
    QuerySpecExecutor(CrudRepositoryExecutor crudExecutor) {
        this.crudExecutor = crudExecutor;
    }

    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        if (plan.kind() != RepositoryMethodPlan.Kind.CRUD) {
            return false;
        }
        String methodName = plan.method().getName();
        return methodName.equals("findAll") || (methodName.startsWith("select") && !methodName.equals("selectById")) || methodName.equals("exists");
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy) {
        return crudExecutor.executeQueryPlan(plan, args, context);
    }
}