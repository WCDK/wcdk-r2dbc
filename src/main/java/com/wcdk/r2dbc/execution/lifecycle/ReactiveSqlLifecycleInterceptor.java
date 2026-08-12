package com.wcdk.r2dbc.execution.lifecycle;

import reactor.core.publisher.Mono;

/**
 * 响应式SQL生命周期拦截器接口。
 * <p>
 * 提供四个生命周期钩子，支持异步操作：
 * <ul>
 *     <li>beforeCompile - SQL编译前</li>
 *     <li>afterCompile - SQL编译后</li>
 *     <li>beforeExecute - SQL执行前</li>
 *     <li>afterExecute - SQL执行后</li>
 * </ul>
 * <p>
 * 与 {@link SqlLifecycleInterceptor} 的区别：
 * <ul>
 *     <li>所有方法返回 {@link Mono}，支持异步操作</li>
 *     <li>适用于需要执行远程调用、数据库查询等异步操作的场景</li>
 *     <li>不会阻塞响应式流</li>
 * </ul>
 * <p>
 * 实现此接口并注册为Spring Bean即可自动生效。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 * @see SqlLifecycleInterceptor 同步版本
 **/
public interface ReactiveSqlLifecycleInterceptor {

    /**
     * SQL编译前调用（异步）。
     * <p>
     * 此时SQL尚未生成，可以进行参数预处理、权限校验等操作。
     * <p>
     * 可以通过以下方式控制执行流程：
     * <ul>
     *     <li>{@link SqlExecutionContext#setStatus(SqlExecutionStatus)} - 设置执行状态</li>
     *     <li>{@link SqlExecutionContext#denyByPermission(String)} - 权限阻止</li>
     *     <li>{@link SqlExecutionContext#skipByAudit(String)} - 审计跳过</li>
     *     <li>{@link SqlExecutionContext#terminateAtCompile(String)} - 编译终止</li>
     *     <li>{@link SqlExecutionContext#cacheHit(Object)} - 缓存命中</li>
     *     <li>{@link SqlExecutionContext#degrade(String)} - 降级执行</li>
     * </ul>
     *
     * @param context SQL执行上下文
     * @return 完成信号
     */
    default Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
        return Mono.empty();
    }

    /**
     * SQL编译后调用（异步）。
     * <p>
     * 此时SQL已生成但尚未执行，可以进行SQL审计、日志记录、SQL修改等操作。
     * <p>
     * 可以通过以下方式控制执行流程：
     * <ul>
     *     <li>{@link SqlExecutionContext#setSql(String)} - 修改SQL</li>
     *     <li>{@link SqlExecutionContext#setStatus(SqlExecutionStatus)} - 设置执行状态</li>
     *     <li>{@link SqlExecutionContext#skipByAudit(String)} - 审计跳过</li>
     *     <li>{@link SqlExecutionContext#terminateAtCompile(String)} - 编译终止</li>
     * </ul>
     *
     * @param context SQL执行上下文
     * @return 完成信号
     */
    default Mono<Void> afterCompileReactive(SqlExecutionContext context) {
        return Mono.empty();
    }

    /**
     * SQL执行前调用（异步）。
     * <p>
     * 此时SQL即将执行，可以进行最终校验、性能计时开始等操作。
     * <p>
     * 可以通过以下方式控制执行流程：
     * <ul>
     *     <li>{@link SqlExecutionContext#setStatus(SqlExecutionStatus)} - 设置执行状态</li>
     *     <li>{@link SqlExecutionContext#denyByPermission(String)} - 权限阻止</li>
     *     <li>{@link SqlExecutionContext#terminateAtExecute(String)} - 执行终止</li>
     * </ul>
     *
     * @param context SQL执行上下文
     * @return 完成信号
     */
    default Mono<Void> beforeExecuteReactive(SqlExecutionContext context) {
        return Mono.empty();
    }

    /**
     * SQL执行后调用（异步）。
     * <p>
     * 此时SQL已执行完成（无论成功或失败），可以进行性能计时结束、结果处理、异常处理等操作。
     *
     * @param context SQL执行上下文
     * @return 完成信号
     */
    default Mono<Void> afterExecuteReactive(SqlExecutionContext context) {
        return Mono.empty();
    }

    /**
     * 获取拦截器执行顺序。
     * <p>
     * 数值越小越先执行，默认为0。
     *
     * @return 执行顺序
     */
    default int getOrder() {
        return 0;
    }
}
