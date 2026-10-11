package com.wcdk.r2dbc.repository;

import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 未配置可选的外部数据库环境时，明确将对应测试标记为跳过。
 * @author wcdk
 */
class ExternalDatabaseIT {
    @Test
    @EnabledIfEnvironmentVariable(named = "WCDK_R2DBC_DM_URL", matches = ".+")
    void damengRepositoryMatrix() {
        verify("DM");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WCDK_R2DBC_ORACLE_URL", matches = ".+")
    void oracleRepositoryMatrix() {
        verify("ORACLE");
    }

    private void verify(String database) {
        String prefix = "WCDK_R2DBC_" + database + "_";
        var options = ConnectionFactoryOptions.parse(required(prefix + "URL")).mutate()
                .option(ConnectionFactoryOptions.USER, required(prefix + "USERNAME"))
                .option(ConnectionFactoryOptions.PASSWORD, required(prefix + "PASSWORD")).build();
        DatabaseMatrixSupport.verify(DatabaseMatrixSupport.connectionFactory(options));
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少真实库测试配置：" + name);
        return value;
    }
}
