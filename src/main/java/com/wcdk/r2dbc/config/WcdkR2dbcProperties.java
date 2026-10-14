package com.wcdk.r2dbc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WCDK R2DBC 配置属性
 *
 * @author WCDK
 *
 * @version 1.0
 */
@Data
@ConfigurationProperties(prefix = "wcdk.r2dbc")
public class WcdkR2dbcProperties {

    private boolean enabled;

    /** XML 方法入参未使用时拒绝执行；false 为历史兼容模式。 */
    private boolean strictUnusedParameters = true;

    /** 可选的数据库驱动类型提示，供单数据源回退和数据库结构初始化使用。 */
    private String databaseType;

    private boolean sqlLogEnabled = false;

    /** 完整 SQL 日志独立开关；同时开启 sql-log-enabled 才输出。 */
    private boolean sqlLogCompleteEnabled;

    /** 日志敏感参数/列名，关键词脱敏与值模式始终生效。 */
    private java.util.Set<String> sensitiveColumns = new java.util.LinkedHashSet<>(java.util.List.of(
            "phone", "mobile", "tel", "cardNo", "idCard", "password", "token", "secret", "credential"));

    /** 存在 ObservationRegistry Bean 时，启用可选的 Micrometer 观测。 */
    private boolean observabilityEnabled;

    /**
     * 事务相关配置。
     */
    private Transaction transaction = new Transaction();

    private boolean snowflakeId;

    /** 雪花节点编号，范围 0 到 1023；多进程部署必须分配不同编号。 */
    private Long snowflakeWorkerId;

    private boolean quoteIdentifier = true;

    private String mapperLocations = "classpath*:repository/**/*.xml";

    private String logicDeleteField = "delFlg";

    private Object logicNotDeleteValue = 0;

    private Object logicDeleteValue = 1;

    /**
     * WCDK 事务扩展配置。
     *
     * @author wcdk
     */
    @Data
    public static class Transaction {

        /**
         * 是否启用 WCDK 自定义事务切面。默认关闭，优先使用 Spring 标准事务 Advisor。
         */
        private boolean aspectEnabled;
    }

    /**
     * 数据库Schema初始化配置
     */
    private DatabaseInitializer databaseInitializer = new DatabaseInitializer();

    /**
     * 数据库初始化配置类
     *
     * @author WCDK
     * @version 1.0
     */
    @Data
    public static class DatabaseInitializer {
        /**
         * 是否启用数据库初始化
         */
        private boolean enabled = false;

        /**
         * SQL文件位置（支持Ant风格路径模式）
         */
        private String sqlLocation = "classpath*:sql/**/*.sql";

        /**
         * 数据库类型（dm/oracle/postgresql/mysql），为空则自动检测
         */
        private String databaseType;

        /**
         * 初始化模式：
         * always - 每次启动都执行
         * never - 从不执行
         * embedded - 仅嵌入式数据库执行
         */
        private String mode = "always";

        /**
         * 是否忽略初始化错误
         */
        private boolean ignoreErrors = true;

        /**
         * 是否在事务中执行
         */
        private boolean executeInTransaction = true;

        /** 初始化的总等待时间，必须为正数；超时取消初始化并使启动失败。 */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(30);
    }
}
