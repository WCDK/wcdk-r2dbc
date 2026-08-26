package com.wcdk.r2dbc.execution.lifecycle;

import com.wcdk.r2dbc.query.xml.SqlCommandType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * SQL执行上下文，包含SQL执行过程中的所有信息。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class SqlExecutionContext {

    private final Method method;

    private final Class<?> repositoryInterface;

    private String sql;

    private Map<String, Object> parameters;

    private final Object[] arguments;

    private long startTime;

    private long endTime;

    private Throwable error;

    private Object result;

    private long resultCount;
    private long returnedRowCount;
    private long affectedRowCount;
    private long emittedItemCount;
    private SqlCommandType commandType;
    private final String executionId = UUID.randomUUID().toString();
    private SqlTerminationType terminationType;

    /**
     * 执行状态 - 使用枚举替代简单的boolean，提供更清晰的语义
     */
    private SqlExecutionStatus status;

    /**
     * 状态原因说明 - 用于记录为什么跳过/终止执行
     */
    private String statusReason;

    public SqlExecutionContext(Method method, Class<?> repositoryInterface, Object[] arguments) {
        this.method = method;
        this.repositoryInterface = repositoryInterface;
        this.arguments = arguments;
        this.status = SqlExecutionStatus.CONTINUE;
        this.commandType = inferCommandType(method.getName());
    }

    public Method getMethod() {
        return method;
    }

    public Class<?> getRepositoryInterface() {
        return repositoryInterface;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public long getDuration() {
        return endTime - startTime;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    /**
     * 获取本次执行实际返回或影响的行数。
     *
     * @return 返回或影响的行数
     */
    public long getResultCount() {
        return resultCount;
    }

    public void setResultCount(long resultCount) {
        this.resultCount = Math.max(0, resultCount);
    }

    public long getReturnedRowCount() {
        return returnedRowCount;
    }

    public long getAffectedRowCount() {
        return affectedRowCount;
    }

    public long getEmittedItemCount() {
        return emittedItemCount;
    }

    public void setReturnedRowCount(long returnedRowCount) {
        this.returnedRowCount = Math.max(0, returnedRowCount);
    }

    public void setAffectedRowCount(long affectedRowCount) {
        this.affectedRowCount = Math.max(0, affectedRowCount);
    }

    public void setEmittedItemCount(long emittedItemCount) {
        this.emittedItemCount = Math.max(0, emittedItemCount);
    }

    public SqlCommandType getCommandType() {
        return commandType;
    }

    public void setCommandType(SqlCommandType commandType) {
        this.commandType = commandType == null ? SqlCommandType.UNKNOWN : commandType;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getStatementId() {
        return repositoryInterface.getName() + "." + method.getName();
    }

    public SqlTerminationType getTerminationType() {
        return terminationType;
    }

    public void setTerminationType(SqlTerminationType terminationType) {
        this.terminationType = terminationType;
    }

    private SqlCommandType inferCommandType(String methodName) {
        String normalized = methodName.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("insert")) return SqlCommandType.INSERT;
        if (normalized.startsWith("update")) return SqlCommandType.UPDATE;
        if (normalized.startsWith("delete")) return SqlCommandType.DELETE;
        if (normalized.startsWith("query") || normalized.startsWith("select")
                || normalized.startsWith("find") || normalized.startsWith("exists")) {
            return SqlCommandType.SELECT;
        }
        return SqlCommandType.UNKNOWN;
    }

    /**
     * 获取执行状态。
     *
     * @return 执行状态
     */
    public SqlExecutionStatus getStatus() {
        return status;
    }

    /**
     * 设置执行状态。
     *
     * @param status 执行状态
     */
    public void setStatus(SqlExecutionStatus status) {
        this.status = status;
    }

    /**
     * 获取状态原因说明。
     *
     * @return 原因说明
     */
    public String getStatusReason() {
        return statusReason;
    }

    /**
     * 设置状态原因说明。
     *
     * @param reason 原因说明
     */
    public void setStatusReason(String reason) {
        this.statusReason = reason;
    }

    /**
     * 判断是否应该继续执行SQL。
     *
     * @return 是否继续执行
     */
    public boolean shouldContinue() {
        return status == SqlExecutionStatus.CONTINUE;
    }

    /**
     * 判断是否已终止（不再执行后续逻辑）。
     *
     * @return 是否已终止
     */
    public boolean isTerminated() {
        return status != null && status.isTerminal();
    }

    public boolean hasError() {
        return error != null;
    }

    /**
     * 快捷方法：设置为权限阻止状态。
     *
     * @param reason 阻止原因
     */
    public void denyByPermission(String reason) {
        this.status = SqlExecutionStatus.DENIED_BY_PERMISSION;
        this.statusReason = reason;
    }

    /**
     * 快捷方法：设置为审计跳过状态。
     *
     * @param reason 跳过原因
     */
    public void skipByAudit(String reason) {
        this.status = SqlExecutionStatus.SKIPPED_BY_AUDIT;
        this.statusReason = reason;
    }

    /**
     * 快捷方法：设置为编译终止状态。
     *
     * @param reason 终止原因
     */
    public void terminateAtCompile(String reason) {
        this.status = SqlExecutionStatus.TERMINATED_AT_COMPILE;
        this.statusReason = reason;
    }

    /**
     * 快捷方法：设置为执行终止状态。
     *
     * @param reason 终止原因
     */
    public void terminateAtExecute(String reason) {
        this.status = SqlExecutionStatus.TERMINATED_AT_EXECUTE;
        this.statusReason = reason;
    }

    /**
     * 快捷方法：设置为缓存命中状态。
     *
     * @param result 缓存的结果
     */
    public void cacheHit(Object result) {
        this.status = SqlExecutionStatus.CACHE_HIT;
        this.result = result;
    }

    /**
     * 快捷方法：设置为降级执行状态。
     *
     * @param reason 降级原因
     */
    public void degrade(String reason) {
        this.status = SqlExecutionStatus.DEGRADED;
        this.statusReason = reason;
    }

    @Override
    public String toString() {
        return "SqlExecutionContext{" +
                "method=" + method.getName() +
                ", repository=" + repositoryInterface.getSimpleName() +
                ", sql='" + sql + '\'' +
                ", status=" + status +
                ", statusReason='" + statusReason + '\'' +
                '}';
    }
}
