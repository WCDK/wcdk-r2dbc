package com.wcdk.r2dbc.config;
import com.wcdk.r2dbc.id.*;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SnowflakeConfigurationTests {
 private ApplicationContextRunner runner(){
  var factory=mock(ConnectionFactory.class);when(factory.getMetadata()).thenReturn(()->"PostgreSQL");
  return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(WcdkR2dbcAutoConfiguration.class))
   .withPropertyValues("wcdk.r2dbc.enabled=true","wcdk.r2dbc.snowflake-id=true").withBean(ConnectionFactory.class,()->factory);
 }
 @Test void explicitNodeOverridesEnvironmentAndCustomResolver(){
  var resolver=mock(WorkerIdResolver.class);
  runner().withPropertyValues("wcdk.r2dbc.snowflake-worker-id=42","WCDK_R2DBC_WORKER_ID=11")
   .withBean(WorkerIdResolver.class,()->resolver).run(c->{assertThat(c).hasNotFailed();
    assertThat(c.getBean(SnowflakeIdGenerator.class).getWorkerId()).isEqualTo(42);verifyNoInteractions(resolver);});
 }
 @Test void supportsEnvironmentAndRegistryResolvers(){
  runner().withPropertyValues("WCDK_R2DBC_WORKER_ID=11").run(c->{assertThat(c).hasNotFailed();
   assertThat(c.getBean(SnowflakeIdGenerator.class).getWorkerId()).isEqualTo(11);});
  runner().withBean(WorkerIdResolver.class,()->()->13).run(c->{assertThat(c).hasNotFailed().hasSingleBean(WorkerIdResolver.class);
   assertThat(c.getBean(SnowflakeIdGenerator.class).getWorkerId()).isEqualTo(13);});
 }
 @Test void rejectsInvalidNodeAndLeaseConflictsAtStartup(){
  for(String value:new String[]{"-1","1024","abc"})runner().withPropertyValues("wcdk.r2dbc.snowflake-worker-id="+value).run(c->assertThat(c).hasFailed());
  runner().withPropertyValues("WCDK_R2DBC_WORKER_ID=1024").run(c->assertThat(c).hasFailed());
  runner().withBean(WorkerIdResolver.class,()->()->{throw new IllegalStateException("节点租约冲突");}).run(c->assertThat(c).hasFailed());
 }
 @Test void userGeneratorBacksOffWithoutResolvingAnotherNode(){
  var resolver=mock(WorkerIdResolver.class);var g=new SnowflakeIdGenerator(14);
  runner().withBean(WorkerIdResolver.class,()->resolver).withBean(SnowflakeIdGenerator.class,()->g).run(c->{
   assertThat(c).hasNotFailed().hasSingleBean(SnowflakeIdGenerator.class);assertThat(c.getBean(SnowflakeIdGenerator.class)).isSameAs(g);verifyNoInteractions(resolver);});
 }
}
