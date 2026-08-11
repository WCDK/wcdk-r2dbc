package com.wcdk.r2dbc.core.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SQL性能监控拦截器示例。
 * <p>
 * 记录SQL执行耗时，超过阈值的SQL会输出警告日志。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
@Component
public class SqlPerformanceInterceptor implements SqlLifecycleInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SqlPerformanceInterceptor.class);

    /**
     * 慢SQL阈值（毫秒）
     */
    private static final long SLOW_SQL_THRESHOLD_MS = 1000;

    @Override
    public void afterCompile(SqlExecutionContext context) {
        log.debug("SQL已编译: {}", context.getSql());
    }

    @Override
    public void afterExecute(SqlExecutionContext context) {
        long durationMs = context.getDuration() / 1_000_000;
        if (durationMs > SLOW_SQL_THRESHOLD_MS) {
            log.warn("检测到慢SQL ({}ms, resultCount={}): {}",
                    durationMs, context.getResultCount(), context.getSql());
        } else {
            log.debug("SQL执行耗时 {}ms, resultCount={}: {}",
                    durationMs, context.getResultCount(), context.getSql());
        }

        if (context.hasError()) {
            log.error("SQL执行失败: {}", context.getSql(), context.getError());
        }
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}
