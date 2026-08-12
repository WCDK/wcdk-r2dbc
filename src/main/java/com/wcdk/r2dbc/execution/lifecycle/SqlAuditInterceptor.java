package com.wcdk.r2dbc.execution.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * SQL审计拦截器示例。
 * <p>
 * 记录所有SQL操作的审计信息。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
@Component
public class SqlAuditInterceptor implements SqlLifecycleInterceptor {

    private static final Logger auditLog = LoggerFactory.getLogger("SQL_AUDIT");

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public void beforeCompile(SqlExecutionContext context) {
        Method method = context.getMethod();
        Class<?> repo = context.getRepositoryInterface();
        auditLog.info("[AUDIT] {} - {}.{} - SQL compilation started",
                LocalDateTime.now().format(FORMATTER),
                repo.getSimpleName(),
                method.getName());
    }

    @Override
    public void afterCompile(SqlExecutionContext context) {
        auditLog.info("[AUDIT] {} - SQL compiled: {}",
                LocalDateTime.now().format(FORMATTER),
                context.getSql());
    }

    @Override
    public void beforeExecute(SqlExecutionContext context) {
        auditLog.info("[AUDIT] {} - SQL execution started",
                LocalDateTime.now().format(FORMATTER));
    }

    @Override
    public void afterExecute(SqlExecutionContext context) {
        long durationMs = context.getDuration() / 1_000_000;
        if (context.hasError()) {
            auditLog.error("[AUDIT] {} - SQL execution failed ({}ms): {} - Error: {}",
                    LocalDateTime.now().format(FORMATTER),
                    durationMs,
                    context.getSql(),
                    context.getError().getMessage());
        } else {
            auditLog.info("[AUDIT] {} - SQL execution completed ({}ms): {}",
                    LocalDateTime.now().format(FORMATTER),
                    durationMs,
                    context.getSql());
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
