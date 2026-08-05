package com.wcdk.r2dbc.core.transaction;

import reactor.core.publisher.Mono;

/**
 * 手动事务接口。
 * <p>
 * 提供手动控制事务的能力，支持显式的提交和回滚操作。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public interface ManualTransaction {

    /**
     * 获取事务状态。
     *
     * @return 事务状态
     */
    TransactionStatus getStatus();

    /**
     * 提交事务。
     * <p>
     * 将所有数据库操作永久保存到数据库。
     *
     * @return 提交结果
     */
    Mono<Boolean> commit();

    /**
     * 回滚事务。
     * <p>
     * 撤销所有未提交的数据库操作。
     *
     * @return 回滚结果
     */
    Mono<Boolean> rollback();

    /**
     * 创建保存点。
     * <p>
     * 在当前事务中创建一个保存点，可以回滚到此保存点。
     *
     * @param savepointName 保存点名称
     * @return 保存点对象
     */
    Mono<Savepoint> createSavepoint(String savepointName);

    /**
     * 回滚到保存点。
     * <p>
     * 回滚到指定的保存点，保留保存点之前的操作。
     *
     * @param savepoint 保存点对象
     * @return 回滚结果
     */
    Mono<Boolean> rollbackToSavepoint(Savepoint savepoint);

    /**
     * 释放保存点。
     * <p>
     * 释放指定的保存点，释放后无法回滚到该保存点。
     *
     * @param savepoint 保存点对象
     * @return 释放结果
     */
    Mono<Boolean> releaseSavepoint(Savepoint savepoint);

    /**
     * 判断事务是否已完成（提交或回滚）。
     *
     * @return 是否已完成
     */
    boolean isCompleted();

    /**
     * 判断事务是否活跃。
     *
     * @return 是否活跃
     */
    boolean isActive();

    /**
     * 判断事务是否仅读取。
     *
     * @return 是否仅读取
     */
    boolean isReadOnly();

    /**
     * 设置事务为仅读取模式。
     * <p>
     * 仅读取事务不会修改数据库，某些数据库可以对此进行优化。
     *
     * @param readOnly 是否仅读取
     */
    void setReadOnly(boolean readOnly);

    /**
     * 设置事务超时时间（秒）。
     * <p>
     * 超时后事务将自动回滚。
     *
     * @param timeoutSeconds 超时时间（秒），0表示不限制
     */
    void setTimeout(int timeoutSeconds);

    /**
     * 获取事务名称。
     *
     * @return 事务名称
     */
    String getName();

    /**
     * 设置事务名称。
     *
     * @param name 事务名称
     */
    void setName(String name);
}
