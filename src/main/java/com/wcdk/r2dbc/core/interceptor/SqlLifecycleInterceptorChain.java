package com.wcdk.r2dbc.core.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SQL生命周期拦截器链管理器。
 * <p>
 * 负责管理和执行所有注册的 {@link SqlLifecycleInterceptor}。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class SqlLifecycleInterceptorChain {

    private static final Logger log = LoggerFactory.getLogger(SqlLifecycleInterceptorChain.class);

    private final List<SqlLifecycleInterceptor> interceptors;

    public SqlLifecycleInterceptorChain(List<SqlLifecycleInterceptor> interceptors) {
        this.interceptors = interceptors != null
                ? interceptors.stream()
                    .sorted(Comparator.comparingInt(SqlLifecycleInterceptor::getOrder))
                    .toList()
                : List.of();
    }

    /**
     * 执行SQL编译前拦截。
     *
     * @param context SQL执行上下文
     * @return 是否跳过后续执行
     */
    public boolean beforeCompile(SqlExecutionContext context) {
        for (SqlLifecycleInterceptor interceptor : interceptors) {
            try {
                interceptor.beforeCompile(context);
                if (context.isSkipped()) {
                    log.debug("Interceptor [{}] skipped execution at beforeCompile", interceptor.getClass().getSimpleName());
                    return true;
                }
            } catch (Exception e) {
                log.error("Interceptor [{}] error at beforeCompile", interceptor.getClass().getSimpleName(), e);
            }
        }
        return false;
    }

    /**
     * 执行SQL编译后拦截。
     *
     * @param context SQL执行上下文
     * @return 是否跳过后续执行
     */
    public boolean afterCompile(SqlExecutionContext context) {
        for (SqlLifecycleInterceptor interceptor : interceptors) {
            try {
                interceptor.afterCompile(context);
                if (context.isSkipped()) {
                    log.debug("Interceptor [{}] skipped execution at afterCompile", interceptor.getClass().getSimpleName());
                    return true;
                }
            } catch (Exception e) {
                log.error("Interceptor [{}] error at afterCompile", interceptor.getClass().getSimpleName(), e);
            }
        }
        return false;
    }

    /**
     * 执行SQL执行前拦截。
     *
     * @param context SQL执行上下文
     * @return 是否跳过后续执行
     */
    public boolean beforeExecute(SqlExecutionContext context) {
        for (SqlLifecycleInterceptor interceptor : interceptors) {
            try {
                interceptor.beforeExecute(context);
                if (context.isSkipped()) {
                    log.debug("Interceptor [{}] skipped execution at beforeExecute", interceptor.getClass().getSimpleName());
                    return true;
                }
            } catch (Exception e) {
                log.error("Interceptor [{}] error at beforeExecute", interceptor.getClass().getSimpleName(), e);
            }
        }
        return false;
    }

    /**
     * 执行SQL执行后拦截。
     *
     * @param context SQL执行上下文
     */
    public void afterExecute(SqlExecutionContext context) {
        for (SqlLifecycleInterceptor interceptor : interceptors) {
            try {
                interceptor.afterExecute(context);
            } catch (Exception e) {
                log.error("Interceptor [{}] error at afterExecute", interceptor.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * 获取拦截器数量。
     *
     * @return 拦截器数量
     */
    public int size() {
        return interceptors.size();
    }

    /**
     * 判断是否有拦截器。
     *
     * @return 是否有拦截器
     */
    public boolean isEmpty() {
        return interceptors.isEmpty();
    }
}
