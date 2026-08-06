package com.wcdk.r2dbc.core.interceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL生命周期拦截器持有者。
 * <p>
 * 用于全局管理拦截器链的创建和获取。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public final class SqlLifecycleInterceptorHolder {

    private static volatile SqlLifecycleInterceptorChain chain;

    private SqlLifecycleInterceptorHolder() {
    }

    /**
     * 初始化拦截器链（支持同步和异步拦截器）。
     *
     * @param syncInterceptors    同步拦截器列表
     * @param reactiveInterceptors 异步拦截器列表
     */
    public static void init(List<SqlLifecycleInterceptor> syncInterceptors,
                            List<ReactiveSqlLifecycleInterceptor> reactiveInterceptors) {
        if (chain == null) {
            synchronized (SqlLifecycleInterceptorHolder.class) {
                if (chain == null) {
                    chain = new SqlLifecycleInterceptorChain(
                            syncInterceptors != null ? syncInterceptors : List.of(),
                            reactiveInterceptors != null ? reactiveInterceptors : List.of()
                    );
                }
            }
        }
    }

    /**
     * 获取拦截器链。
     *
     * @return 拦截器链
     */
    public static SqlLifecycleInterceptorChain getChain() {
        if (chain == null) {
            synchronized (SqlLifecycleInterceptorHolder.class) {
                if (chain == null) {
                    chain = new SqlLifecycleInterceptorChain(List.of(), List.of());
                }
            }
        }
        return chain;
    }

    /**
     * 重置拦截器链（仅用于测试）。
     */
    public static void reset() {
        synchronized (SqlLifecycleInterceptorHolder.class) {
            chain = null;
        }
    }
}
