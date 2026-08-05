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
 * 支持同步和异步两种执行模式：
 * <ul>
 *     <li>同步模式：使用 {@link #beforeCompile}、{@link #afterCompile} 等方法</li>
 *     <li>异步模式：使用 {@link #beforeCompileReactive}、{@link #afterCompileReactive} 等方法</li>
 * </ul>
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

    // ==================== 同步方法（兼容旧API） ====================

    /**
     * 执行编译前拦截（同步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     * @deprecated 使用 {@link #beforeCompileReactive(SqlLifecycleInterceptorChain, SqlExecutionContext)} 替代
     */
    @Deprecated
    public boolean beforeCompile(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.beforeCompile(context);
    }

    /**
     * 执行编译后拦截（同步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     * @deprecated 使用 {@link #afterCompileReactive(SqlLifecycleInterceptorChain, SqlExecutionContext)} 替代
     */
    @Deprecated
    public boolean afterCompile(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.afterCompile(context);
    }

    /**
     * 执行执行前拦截（同步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     * @deprecated 使用 {@link #beforeExecuteReactive(SqlLifecycleInterceptorChain, SqlExecutionContext)} 替代
     */
    @Deprecated
    public boolean beforeExecute(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.beforeExecute(context);
    }

    /**
     * 执行执行后拦截（同步）。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @deprecated 使用 {@link #afterExecuteReactive(SqlLifecycleInterceptorChain, SqlExecutionContext)} 替代
     */
    @Deprecated
    public void afterExecute(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        chain.afterExecute(context);
    }

    // ==================== 异步方法（推荐使用） ====================

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
