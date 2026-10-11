package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.R2dbcUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实达梦 R2DBC 数据库的集成冒烟测试。
 * <p>
 * 默认禁用，避免本地单元测试和持续集成依赖已运行的数据库。
 * 使用 {@code -Dwcdk.r2dbc.dm.integration=true} 启用，
 * 并通过系统属性或环境变量提供连接配置。
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "wcdk.r2dbc.dm.integration", matches = "true")
class DmR2dbcIntegrationTests {

    private static final String SELECT_ONE_SQL = "SELECT 1 AS V FROM DUAL";

    @Test
    void autoConfigurationConnectsToConfiguredDmDatabase() {
        String url = requiredSetting("wcdk.r2dbc.dm.url", "WCDK_R2DBC_DM_URL");
        String username = requiredSetting("wcdk.r2dbc.dm.username", "WCDK_R2DBC_DM_USERNAME");
        String password = requiredSetting("wcdk.r2dbc.dm.password", "WCDK_R2DBC_DM_PASSWORD");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WcdkR2dbcAutoConfiguration.class))
                .withPropertyValues(
                        "wcdk.r2dbc.enabled=true",
                        "spring.r2dbc.url=" + url,
                        "spring.r2dbc.username=" + username,
                        "spring.r2dbc.password=" + password,
                        "spring.r2dbc.pool.enabled=false",
                        "database.type=dm")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DatabaseClient.class);
                    assertThat(context).hasSingleBean(R2dbcUtil.class);

                    DatabaseClient databaseClient = context.getBean(DatabaseClient.class);
                    StepVerifier.create(databaseClient.sql(SELECT_ONE_SQL)
                                    .map((row, metadata) -> row.get(0, Number.class).intValue())
                                    .one())
                            .expectNext(1)
                            .verifyComplete();
                });
    }

    @Test
    void r2dbcUtilExecutesSimpleDmQuery() {
        String url = requiredSetting("wcdk.r2dbc.dm.url", "WCDK_R2DBC_DM_URL");
        String username = requiredSetting("wcdk.r2dbc.dm.username", "WCDK_R2DBC_DM_USERNAME");
        String password = requiredSetting("wcdk.r2dbc.dm.password", "WCDK_R2DBC_DM_PASSWORD");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WcdkR2dbcAutoConfiguration.class))
                .withPropertyValues(
                        "wcdk.r2dbc.enabled=true",
                        "spring.r2dbc.url=" + url,
                        "spring.r2dbc.username=" + username,
                        "spring.r2dbc.password=" + password,
                        "spring.r2dbc.pool.enabled=false",
                        "database.type=dm")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    R2dbcUtil r2dbcUtil = context.getBean(R2dbcUtil.class);
                    StepVerifier.create(r2dbcUtil.queryOne(SELECT_ONE_SQL,
                                    (row, metadata) -> row.get(0, Number.class).intValue()))
                            .expectNext(1)
                            .verifyComplete();
                });
    }

    private static String requiredSetting(String propertyName, String environmentName) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String environment = System.getenv(environmentName);
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        throw new IllegalStateException("缺少达梦集成测试配置：请设置 -D" + propertyName
                + " 或环境变量 " + environmentName);
    }
}
