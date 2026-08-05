package com.wcdk.r2dbc.core.transaction;

import java.time.LocalDateTime;

/**
 * 事务保存点接口。
 * <p>
 * 用于在事务中创建保存点，支持部分回滚。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public interface Savepoint {

    /**
     * 获取保存点名称。
     *
     * @return 保存点名称
     */
    String getName();

    /**
     * 获取保存点ID。
     *
     * @return 保存点ID
     */
    int getId();

    /**
     * 获取创建时间。
     *
     * @return 创建时间
     */
    LocalDateTime getCreatedAt();

    /**
     * 判断保存点是否有效。
     *
     * @return 是否有效
     */
    boolean isValid();
}
