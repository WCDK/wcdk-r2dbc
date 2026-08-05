package com.wcdk.r2dbc.core.interceptor;

/**
 * SQL执行状态枚举，用于区分不同的执行结果。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public enum SqlExecutionStatus {

    /**
     * 继续执行 - SQL将继续执行
     */
    CONTINUE("继续执行", false),

    /**
     * 正常完成 - SQL正常执行完成（可能无结果）
     */
    COMPLETED("正常完成", true),

    /**
     * 权限阻止 - 被权限拦截器阻止执行
     */
    DENIED_BY_PERMISSION("权限阻止", true),

    /**
     * 审计跳过 - 被审计策略跳过（如只读审计、采样跳过等）
     */
    SKIPPED_BY_AUDIT("审计跳过", true),

    /**
     * 编译终止 - SQL编译阶段主动终止
     */
    TERMINATED_AT_COMPILE("编译终止", true),

    /**
     * 执行终止 - SQL执行阶段主动终止
     */
    TERMINATED_AT_EXECUTE("执行终止", true),

    /**
     * 缓存命中 - 从缓存返回结果，无需执行SQL
     */
    CACHE_HIT("缓存命中", true),

    /**
     * 降级执行 - 降级策略触发，使用备用逻辑
     */
    DEGRADED("降级执行", true);

    private final String description;

    private final boolean terminal;

    SqlExecutionStatus(String description, boolean terminal) {
        this.description = description;
        this.terminal = terminal;
    }

    /**
     * 获取状态描述。
     *
     * @return 描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 是否是终态（即不再继续执行后续拦截器）。
     *
     * @return 是否终态
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 判断当前状态是否表示被跳过（不执行SQL）。
     *
     * @return 是否被跳过
     */
    public boolean isSkipped() {
        return this != CONTINUE && this != COMPLETED;
    }
}
