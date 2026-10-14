package com.wcdk.r2dbc.config;
import com.wcdk.r2dbc.execution.lifecycle.*;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class AuditConfigurationTests {
    private ApplicationContextRunner runner(){
        var factory=mock(ConnectionFactory.class);when(factory.getMetadata()).thenReturn(()->"PostgreSQL");
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(WcdkR2dbcAutoConfiguration.class))
                .withPropertyValues("wcdk.r2dbc.enabled=true","wcdk.r2dbc.snowflake-worker-id=31").withBean(ConnectionFactory.class,()->factory);
    }
    @Test void startupDoesNotLogCredentialsEmbeddedInTheConnectionUrl(org.springframework.boot.test.system.CapturedOutput output){
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(WcdkR2dbcAutoConfiguration.class))
                .withPropertyValues("wcdk.r2dbc.enabled=true","wcdk.r2dbc.snowflake-worker-id=31","spring.r2dbc.pool.enabled=false",
                        "spring.r2dbc.url=r2dbc:postgresql://test:startup-private@localhost/test")
                .run(c->assertThat(c).hasNotFailed());
        assertThat(output.getAll()).doesNotContain("startup-private");
    }
    @Test void disabledLoggingDoesNotInstallAnAuditOutputter(){
        runner().run(c->assertThat(c).hasNotFailed().doesNotHaveBean(SqlAuditInterceptor.class));
    }
    @Test void loggingEnabledInstallsAuditIntoTheLifecycleChain(){
        runner().withPropertyValues("wcdk.r2dbc.sql-log-enabled=true").run(c->{
            assertThat(c).hasNotFailed().hasSingleBean(SqlAuditInterceptor.class);
            assertThat(c.getBean(SqlLifecycleInterceptorChain.class).size()).isEqualTo(1);
        });
    }
    @Test void userAuditReplacesDefaultAndComponentScanningCannotBypassTheGate(){
        assertThat(SqlAuditInterceptor.class.isAnnotationPresent(org.springframework.stereotype.Component.class)).isFalse();
        var supplied=new SqlAuditInterceptor();
        runner().withPropertyValues("wcdk.r2dbc.sql-log-enabled=true").withBean(SqlAuditInterceptor.class,()->supplied)
                .run(c->assertThat(c.getBean(SqlAuditInterceptor.class)).isSameAs(supplied));
    }
}
