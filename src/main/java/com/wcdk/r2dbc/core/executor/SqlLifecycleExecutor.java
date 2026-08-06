package com.wcdk.r2dbc.core.executor;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorHolder;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * R2DBC SQL生命周期执行器，负责管理SQL执行的生命周期（编译前、编译后、执行前、执行后）。
 * <p>
 * 使用异步方法管理拦截器链：
 * {@link #beforeCompileReactive}、{@link #afterCompileReactive}、
 * {@link #beforeExecuteReactive}、{@link #afterExecuteReactive}
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class SqlLifecycleExecutor {

    /**
     * 创建SQL执行上下文。
     *
     * @param methodName 方法名
     * @param parameters 参数
     * @return SQL执行上下文
     */
    public SqlExecutionContext createContext(String methodName, Map<String, Object> parameters) {
        SqlExecutionContext context = new SqlExecutionContext(findMethod(methodName), R2dbcUtil.class, null);
        context.setParameters(parameters);
        return context;
    }

    // ==================== 异步方法 ====================

    /**
     * 执行编译前拦截（异步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public Mono<Boolean> beforeCompileReactive(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.beforeCompileReactive(context);
    }

    /**
     * 执行编译后拦截（异步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public Mono<Boolean> afterCompileReactive(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.afterCompileReactive(context);
    }

    /**
     * 执行执行前拦截（异步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public Mono<Boolean> beforeExecuteReactive(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.beforeExecuteReactive(context);
    }

    /**
     * 执行执行后拦截（异步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 完成信号
     */
    public Mono<Void> afterExecuteReactive(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.afterExecuteReactive(context);
    }

    /**
     * 获取拦截器链。
     *
     * @return 拦截器链
     */
    public SqlLifecycleInterceptorChain getChain() {
        return SqlLifecycleInterceptorHolder.getChain();
    }

    private Method findMethod(String methodName) {
        try {
            return R2dbcUtil.class.getMethod(methodName, String.class, Map.class);
        } catch (NoSuchMethodException e) {
            try {
                return R2dbcUtil.class.getDeclaredMethod(methodName, String.class, Map.class);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("Method not found: " + methodName, ex);
            }
        }
    }
}
