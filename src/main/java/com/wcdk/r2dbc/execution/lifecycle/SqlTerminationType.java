package com.wcdk.r2dbc.execution.lifecycle;

/**
 * 单次 SQL 执行观察到的响应式流终止信号。
 *
 * @author WCDK
 **/
public enum SqlTerminationType {
    COMPLETE,
    ERROR,
    CANCELLED
}
