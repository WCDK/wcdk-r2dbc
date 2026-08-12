package com.wcdk.r2dbc.transaction;

/**
 * 事务状态枚举。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public enum TransactionStatus {

    /**
     * 新建事务
     */
    NEW,

    /**
     * 事务已激活
     */
    ACTIVE,

    /**
     * 事务已提交
     */
    COMMITTED,

    /**
     * 事务已回滚
     */
    ROLLED_BACK,

    /**
     * 事务已完成
     */
    COMPLETED,

    /**
     * 事务标记为仅读取
     */
    READ_ONLY,

    /**
     * 事务超时
     */
    TIMEOUT,

    /**
     * 事务异常
     */
    FAILED
}
