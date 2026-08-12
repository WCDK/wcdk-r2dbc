package com.wcdk.r2dbc.execution;

/***
 * SQL 执行选项。
 * @author wcdk
 **/
public record ExecutionOptions(boolean lifecycleEnabled, boolean observeMetrics) {
    public static final ExecutionOptions DEFAULT = new ExecutionOptions(true, true);
}