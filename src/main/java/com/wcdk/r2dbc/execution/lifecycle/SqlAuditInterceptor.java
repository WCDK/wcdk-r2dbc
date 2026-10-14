package com.wcdk.r2dbc.execution.lifecycle;

import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计输出与 SQL 日志共用开关，输出原始原因、完整 SQL 和错误消息。
 * @author wcdk
 */
public class SqlAuditInterceptor implements SqlLifecycleInterceptor {
    private static final Logger log = LoggerFactory.getLogger("SQL_AUDIT");
    private final R2dbcSqlLogger logger;
    public SqlAuditInterceptor() { this(new R2dbcSqlLogger(new WcdkR2dbcProperties(), new WcdkSpringR2dbcProperties())); }
    public SqlAuditInterceptor(R2dbcSqlLogger logger) { this.logger = java.util.Objects.requireNonNull(logger); }
    @Override public void afterExecute(SqlExecutionContext context) {
        if (!logger.isEnabled()) return;
        log.info("AUDIT executionId={} statementId={} termination={} reason={} durationMs={} SQL={} error={}",
                context.getExecutionId(), context.getStatementId(), context.getTerminationType(),
                context.getStatusReason(), context.getDuration()/1_000_000,
                logger.completeSql(context.getSql(), context.getParameters()),
                context.hasError() ? context.getError().getMessage() : "无");
    }
    @Override public int getOrder() { return -100; }
}
