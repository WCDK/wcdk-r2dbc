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

    private boolean skipped;

    public SqlExecutionContext(Method method, Class<?> repositoryInterface, Object[] arguments) {
        this.method = method;
        this.repositoryInterface = repositoryInterface;
        this.arguments = arguments;
        this.skipped = false;
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

    public boolean isSkipped() {
        return skipped;
    }

    public void setSkipped(boolean skipped) {
        this.skipped = skipped;
    }

    public boolean hasError() {
        return error != null;
    }

    @Override
    public String toString() {
        return "SqlExecutionContext{" +
                "method=" + method.getName() +
                ", repository=" + repositoryInterface.getSimpleName() +
                ", sql='" + sql + '\'' +
                ", skipped=" + skipped +
                '}';
    }
}
