package com.wcdk.r2dbc.transaction;

/**
 * 事务状态枚举。
 *
 * @author WCDK
 *
 * @version 1.0
 */
public enum TransactionStatus {

    /**
     * 新建事务
     */
    NEW,

    /**
     * 事务已激活
     */
    ACTIVE,

    /** 提交在途，关闭必须等待提交终态。 */
    COMMITTING,

    /** 关闭或回滚在途，不接受新的提交操作。 */
    CLOSING,

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
