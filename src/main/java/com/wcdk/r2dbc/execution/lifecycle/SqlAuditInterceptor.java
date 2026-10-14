package com.wcdk.r2dbc.execution.lifecycle;

import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 审计输出与 SQL 日志共用开关和脱敏管线，仅记录执行终态。 */
public class SqlAuditInterceptor implements SqlLifecycleInterceptor {
    private static final Logger log = LoggerFactory.getLogger("SQL_AUDIT");
    private final R2dbcSqlLogger logger;
    public SqlAuditInterceptor() { this(new R2dbcSqlLogger(new WcdkR2dbcProperties(), new WcdkSpringR2dbcProperties())); }
    public SqlAuditInterceptor(R2dbcSqlLogger logger) { this.logger = java.util.Objects.requireNonNull(logger); }
    @Override public void afterExecute(SqlExecutionContext context) {
        if (!logger.isEnabled()) return;
        log.info("AUDIT executionId={} statementId={} termination={} reason={} durationMs={} SQL={} error={}",
                context.getExecutionId(), context.getStatementId(), context.getTerminationType(),
                logger.sanitizeText(context.getStatusReason(), context.getParameters()), context.getDuration()/1_000_000,
                logger.sanitizeText(context.getSql(), context.getParameters()),
                context.hasError() ? logger.sanitizeText(context.getError().getMessage(), context.getParameters()) : "none");
    }
    @Override public int getOrder() { return -100; }
}
