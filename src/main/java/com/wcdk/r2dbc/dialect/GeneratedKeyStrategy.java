package com.wcdk.r2dbc.dialect;

/**
 * 数据库生成键策略。
 * @author wcdk
 */
public enum GeneratedKeyStrategy {
    CLAUSE_RETURNING, DRIVER_GENERATED_KEYS, NONE
}
