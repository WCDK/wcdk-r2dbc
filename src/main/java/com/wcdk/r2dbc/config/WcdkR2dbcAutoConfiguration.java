package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.core.RepositoryProxyFactory;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptor;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorHolder;
import com.wcdk.r2dbc.core.transaction.TransactionManager;
import com.wcdk.r2dbc.core.transaction.TransactionTemplate;
import com.wcdk.r2dbc.core.xml.RepositoryXmlRegistry;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceAspect;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;
import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

/**
 * WCDK R2DBC 自动配置。
 *
 * @author WCDK
 * @version 1.0
 * @date 2026/7/21
 **/
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration",
        "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"
})
@ConditionalOnClass(DatabaseClient.class)
@EnableConfigurationProperties({WcdkR2dbcProperties.class, WcdkSpringR2dbcProperties.class})
@ConditionalOnProperty(prefix = "Wcdk.r2dbc", name = "enabled", havingValue = "true")
@Primary
public class WcdkR2dbcAutoConfiguration {

    @Bean
    @Conditional(WcdkR2dbcDataSourcesCondition.class)
    @ConditionalOnMissingBean(ConnectionFactory.class)
    @Role(ROLE_INFRASTRUCTURE)
    public ConnectionFactory connectionFactory(WcdkSpringR2dbcProperties properties) {
        Map<String, WcdkSpringR2dbcProperties.DataSourceProperties> dataSources = properties.getDataSources();
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IllegalStateException("spring.r2dbc.data-sources must be configured when Spring R2DBC ConnectionFactory is missing");
        }
        Map<String, ConnectionFactory> connectionFactories = new LinkedHashMap<>();
        dataSources.forEach((name, dataSourceProperties) -> connectionFactories.put(name, createConnectionFactory(name, dataSourceProperties, properties.getPool())));
        return new DynamicRoutingConnectionFactory(properties.getPrimary(), connectionFactories);
    }

    @Bean
    @ConditionalOnMissingBean(DatabaseClient.class)
    @Role(ROLE_INFRASTRUCTURE)
    public DatabaseClient databaseClient(ConnectionFactory connectionFactory) {
        return DatabaseClient.create(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveTransactionManager.class)
    @Role(ROLE_INFRASTRUCTURE)
    public R2dbcTransactionManager r2dbcTransactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean(TransactionalOperator.class)
    @Role(ROLE_INFRASTRUCTURE)
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }

    @Bean
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnMissingBean
    @Role(ROLE_INFRASTRUCTURE)
    public R2dbcDataSourceAspect r2dbcDataSourceAspect() {
        return new R2dbcDataSourceAspect();
    }

    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public R2dbcUtil r2dbcUtil(DatabaseClient databaseClient,
                               ObjectProvider<R2dbcEntityTemplate> entityTemplate,
                               ObjectProvider<TransactionalOperator> transactionalOperator,
                               WcdkR2dbcProperties properties,
                               WcdkSpringR2dbcProperties springR2dbcProperties,
                               ObjectProvider<TransactionManager> transactionManagerProvider) {
        return new R2dbcUtil(databaseClient, entityTemplate.getIfAvailable(), transactionalOperator.getIfAvailable(), 
                properties, springR2dbcProperties, transactionManagerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    @Role(ROLE_INFRASTRUCTURE)
    public TransactionManager wcdkTransactionManager(ConnectionFactory connectionFactory) {
        return new TransactionManager(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean(TransactionTemplate.class)
    @Role(ROLE_INFRASTRUCTURE)
    public TransactionTemplate transactionTemplate(TransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public RepositoryXmlRegistry mapperXmlRegistry(ResourcePatternResolver resourcePatternResolver, WcdkR2dbcProperties properties) {
        return new RepositoryXmlRegistry(resourcePatternResolver, properties);
    }

    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public RepositoryProxyFactory repositoryProxyFactory(R2dbcUtil r2dbcUtil,
                                                          WcdkR2dbcProperties properties,
                                                          RepositoryXmlRegistry repositoryXmlRegistry,
                                                          WcdkSpringR2dbcProperties springR2dbcProperties) {
        return new RepositoryProxyFactory(r2dbcUtil, properties, repositoryXmlRegistry, springR2dbcProperties);
    }

    @Bean
    public SqlLifecycleInterceptorInitializer sqlLifecycleInterceptorInitializer(
            ObjectProvider<List<SqlLifecycleInterceptor>> interceptorsProvider) {
        return new SqlLifecycleInterceptorInitializer(interceptorsProvider.getIfAvailable());
    }

    private ConnectionFactory createConnectionFactory(String name, WcdkSpringR2dbcProperties.DataSourceProperties dsProperties, WcdkSpringR2dbcProperties.Pool globalPool) {
        if (dsProperties == null || dsProperties.getUrl() == null || dsProperties.getUrl().isBlank()) {
            throw new IllegalArgumentException("R2DBC data source url is blank: " + name);
        }
        ConnectionFactoryOptions.Builder builder = ConnectionFactoryOptions.builder()
                .from(ConnectionFactoryOptions.parse(dsProperties.getUrl()));
        if (dsProperties.getUsername() != null && !dsProperties.getUsername().isBlank()) {
            builder.option(USER, dsProperties.getUsername());
        }
        if (dsProperties.getPassword() != null) {
            builder.option(PASSWORD, dsProperties.getPassword());
        }
        if (dsProperties.getProperties() != null) {
            dsProperties.getProperties().forEach((key, value) -> builder.option(Option.valueOf(key), value));
        }
        ConnectionFactory connectionFactory = ConnectionFactories.get(builder.build());
        return poolConnectionFactory(name, connectionFactory, dsProperties, globalPool);
    }

    private ConnectionFactory poolConnectionFactory(String name, ConnectionFactory connectionFactory, WcdkSpringR2dbcProperties.DataSourceProperties dsProperties, WcdkSpringR2dbcProperties.Pool globalPool) {
        WcdkSpringR2dbcProperties.Pool poolConfig = dsProperties.getPool() != null ? dsProperties.getPool() : globalPool;
        if (poolConfig == null || !poolConfig.isEnabled()) {
            return connectionFactory;
        }
        ConnectionPoolConfiguration.Builder poolBuilder = ConnectionPoolConfiguration.builder(connectionFactory)
                .name(name)
                .maxSize(poolConfig.getMaxSize())
                .maxIdleTime(poolConfig.getMaxIdleTime())
                .maxLifeTime(poolConfig.getMaxLifeTime())
                .maxAcquireTime(poolConfig.getMaxAcquireTime())
                .acquireRetry(poolConfig.getAcquireRetry())
                .maxCreateConnectionTime(poolConfig.getMaxCreateConnectionTime())
                .initialSize(poolConfig.getInitialSize());
        if (poolConfig.getValidationQuery() != null && !poolConfig.getValidationQuery().isBlank()) {
            poolBuilder.validationQuery(poolConfig.getValidationQuery());
        }
        return new ConnectionPool(poolBuilder.build());
    }
}
