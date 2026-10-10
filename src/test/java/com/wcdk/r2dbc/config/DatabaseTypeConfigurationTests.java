package com.wcdk.r2dbc.config;

import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseTypeConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WcdkR2dbcAutoConfiguration.class))
            .withPropertyValues("wcdk.r2dbc.enabled=true", "spring.r2dbc.url=r2dbc:postgresql://localhost/demo",
                    "spring.r2dbc.pool.enabled=false", "spring.r2dbc.username=test");

    @ParameterizedTest
    @CsvSource({"postgresql,mysql,PostgreSQL", ",mysql,MySQL", "mysql,,MySQL", ",,PostgreSQL"})
    void fallbackIsReachableAndNewPropertyWins(String current, String legacy, String expected, CapturedOutput output) {
        runner.withPropertyValues("wcdk.r2dbc.database-type=" + (current == null ? "" : current),
                        "database.type=" + (legacy == null ? "" : legacy))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConnectionFactory.class);
                    assertThat(context).hasBean("singleConnectionFactory");
                    assertThat(context.getBean(ConnectionFactory.class).getMetadata().getName()).isEqualTo(expected);
                    if (legacy != null) assertThat(output).contains("database.type 已弃用", "新属性优先");
                });
    }

    @Test
    void bootConnectionFactoryWinsAndIgnoresWcdkDriverOverride() {
        runner.withConfiguration(AutoConfigurations.of(R2dbcAutoConfiguration.class, WcdkR2dbcAutoConfiguration.class))
                .withPropertyValues("wcdk.r2dbc.database-type=mysql")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConnectionFactory.class);
                    assertThat(context).doesNotHaveBean("singleConnectionFactory");
                    assertThat(context.getBean(ConnectionFactory.class).getMetadata().getName()).isEqualTo("PostgreSQL");
                });
    }

    @Test
    void multiSourcePropertiesExcludeSingleSourceFallback() {
        runner.withPropertyValues("spring.r2dbc.primary=master",
                        "spring.r2dbc.data-sources.master.url=r2dbc:postgresql://localhost/demo",
                        "spring.r2dbc.data-sources.master.username=test")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConnectionFactory.class);
                    assertThat(context).doesNotHaveBean("singleConnectionFactory");
                    assertThat(context.getBean(ConnectionFactory.class)).isInstanceOf(DynamicRoutingConnectionFactory.class);
                });
    }

    @Test
    void schemaInitializerUsesResolvedHintAndKeepsItsExplicitOverride() {
        runner.withPropertyValues("wcdk.r2dbc.database-type=postgresql", "database.type=mysql",
                        "wcdk.r2dbc.database-initializer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var initializer = context.getBean(DatabaseSchemaInitializer.class);
                    assertThat((String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            initializer, "detectDatabaseType", "")).isEqualTo("postgresql");
                    assertThat((String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            initializer, "detectDatabaseType", "oracle")).isEqualTo("oracle");
                });
    }

    @Test
    void exposesGeneratedConfigurationMetadata() throws Exception {
        try (var stream = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(stream).isNotNull();
            String metadata = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(metadata).contains("wcdk.r2dbc.database-type", "database.type", "replacement", "deprecation");
        }
    }
}
