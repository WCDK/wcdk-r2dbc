package com.wcdk.r2dbc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WCDK R2DBC 配置属性
 *
 * @auther WCDK
 * @date 2026/7/20
 * @version 1.0
 **/
@Data
@ConfigurationProperties(prefix = "wcdk.r2dbc")
public class WcdkR2dbcProperties {

    private boolean enabled;

    private boolean sqlLogEnabled = true;

    private boolean snowflakeId;

    private boolean quoteIdentifier = true;

    private String mapperLocations = "classpath*:repository/**/*.xml";

    private String logicDeleteField = "delFlg";

    private Object logicNotDeleteValue = 0;

    private Object logicDeleteValue = 1;

    /**
     * 数据库Schema初始化配置
     */
    private DatabaseInitializer databaseInitializer = new DatabaseInitializer();

    /**
     * 数据库初始化配置类
     *
     * @auther WCDK
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
    }
}
