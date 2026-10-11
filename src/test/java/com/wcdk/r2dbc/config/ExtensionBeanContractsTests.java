package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.execution.*;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExtensionBeanContractsTests {
    private ApplicationContextRunner runner() {
        var factory = mock(ConnectionFactory.class); when(factory.getMetadata()).thenReturn(() -> "PostgreSQL");
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(WcdkR2dbcAutoConfiguration.class))
                .withPropertyValues("wcdk.r2dbc.enabled=true").withBean(ConnectionFactory.class, () -> factory);
    }
    @Test void suppliesDefaultsOnlyWhenNoAssignableUserBeanExists() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ParameterBinder.class)
                    .hasSingleBean(R2dbcRowMapper.class).hasSingleBean(R2dbcSqlLogger.class);
        });
        checkReplacement(ParameterBinder.class, ParameterBinder.class);
        checkReplacement(ParameterBinder.class, CustomBinder.class);
        checkReplacement(R2dbcRowMapper.class, R2dbcRowMapper.class);
        checkReplacement(R2dbcRowMapper.class, CustomMapper.class);
        checkReplacement(R2dbcSqlLogger.class, R2dbcSqlLogger.class);
        checkReplacement(R2dbcSqlLogger.class, CustomLogger.class);
    }
    private <T, S extends T> void checkReplacement(Class<T> base, Class<S> declared) {
        S supplied = mock(declared);
        runner().withBean("custom", declared, () -> supplied).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(base);
            assertThat(context.getBean(base)).isSameAs(supplied);
        });
    }
    @Test void multipleCandidatesRequirePrimaryAndDoNotCreateFallbacks() {
        checkMultiple(ParameterBinder.class); checkMultiple(R2dbcRowMapper.class); checkMultiple(R2dbcSqlLogger.class);
    }
    private <T> void checkMultiple(Class<T> type) {
        T first = mock(type); T second = mock(type);
        runner().withBean("first", type, () -> first).withBean("second", type, () -> second)
                .run(context -> assertThat(context).hasFailed());
        runner().withBean("first", type, () -> first, definition -> definition.setPrimary(true))
                .withBean("second", type, () -> second).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(type)).hasSize(2);
                    assertThat(context.getBean(type)).isSameAs(first);
                });
    }
    static class CustomBinder extends ParameterBinder {}
    static class CustomMapper extends R2dbcRowMapper {}
    static class CustomLogger extends R2dbcSqlLogger {
        CustomLogger() { super(new WcdkR2dbcProperties(), new WcdkSpringR2dbcProperties()); }
    }
}
