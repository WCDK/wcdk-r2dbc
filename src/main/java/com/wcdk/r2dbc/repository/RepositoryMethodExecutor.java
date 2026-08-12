package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import reactor.util.context.ContextView;

/***
 * Repository 方法执行器统一契约。
 * @author wcdk
 **/
public interface RepositoryMethodExecutor {

    /***
     * 判断执行器是否支持指定方法计划。
     *
     * @param plan Repository 方法计划
     * @return 是否支持
     * @author wcdk
     **/
    boolean supports(RepositoryMethodPlan plan);

    /***
     * 执行 Repository 方法计划。
     *
     * @param plan Repository 方法计划
     * @param args 方法参数
     * @param context Reactor 上下文
     * @param proxy 代理对象
     * @return 执行结果
     * @author wcdk
     **/
    Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy);
}