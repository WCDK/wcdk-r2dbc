package com.wcdk.r2dbc.core;

/***
 * Repository 方法执行器。
 * @author wcdk
 */
interface RepositoryMethodExecutor {
    boolean supports(RepositoryMethodPlan plan);
    Object execute(RepositoryMethodPlan plan, Object[] args);
}
