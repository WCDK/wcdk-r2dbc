package com.wcdk.r2dbc.core.executor;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorHolder;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * R2DBC SQL生命周期执行器，负责管理SQL执行的生命周期（编译前、编译后、执行前、执行后）。
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

    /**
     * 执行编译前拦截。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public boolean beforeCompile(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.beforeCompile(context);
    }

    /**
     * 执行编译后拦截。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public boolean afterCompile(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.afterCompile(context);
    }

    /**
     * 执行执行前拦截。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     * @return 是否中断执行
     */
    public boolean beforeExecute(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        return chain.beforeExecute(context);
    }

    /**
     * 执行执行后拦截。
     *
     * @param chain   拦截器链
     * @param context 执行上下文
     */
    public void afterExecute(SqlLifecycleInterceptorChain chain, SqlExecutionContext context) {
        chain.afterExecute(context);
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
