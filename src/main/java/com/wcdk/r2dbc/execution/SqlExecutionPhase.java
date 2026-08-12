package com.wcdk.r2dbc.execution;

/**
 * 用于生命周期诊断和可选观测的稳定阶段。
 *
 * @author WCDK
 **/
public enum SqlExecutionPhase {
    PREPARE,
    COMPILE,
    REWRITE,
    VALIDATE,
    BIND,
    EXECUTE,
    RESULT,
    FINALLY
}
