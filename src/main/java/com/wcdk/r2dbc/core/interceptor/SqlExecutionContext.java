package com.wcdk.r2dbc.core.interceptor;

import java.lang.reflect.Method;
import java.util.Map;

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
