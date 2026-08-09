package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.core.RepositoryProxyFactory;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptor;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorHolder;
import com.wcdk.r2dbc.core.transaction.TransactionManager;
import com.wcdk.r2dbc.core.transaction.TransactionTemplate;
import com.wcdk.r2dbc.core.transaction.TransactionalAspect;
import com.wcdk.r2dbc.core.xml.RepositoryXmlRegistry;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceAspect;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.ConnectionFactoryProvider;
import io.r2dbc.spi.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * 支持两种配置模式：
 * 1. 多数据源：spring.r2dbc.data-sources.master.url=...
 * 2. 单数据源：spring.r2dbc.url=...
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
@ConditionalOnProperty(prefix = "wcdk.r2dbc", name = "enabled", havingValue = "true")
@Primary
public class WcdkR2dbcAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WcdkR2dbcAutoConfiguration.class);

    /**
     * 多数据源配置 - 动态路由连接工厂
     */
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

    /**
     * 单数据源配置 - 基于 spring.r2dbc.url 配置
     */
    @Bean
    @ConditionalOnMissingBean(ConnectionFactory.class)
    @Role(ROLE_INFRASTRUCTURE)
    public ConnectionFactory singleConnectionFactory(
            WcdkSpringR2dbcProperties properties,
            @Value("${spring.r2dbc.url:}") String r2dbcUrl,
            @Value("${spring.r2dbc.username:}") String username,
            @Value("${spring.r2dbc.password:}") String password,
            @Value("${database.type:}") String databaseType) {
        
        // 如果没有配置 spring.r2dbc.url，跳过创建
        if (r2dbcUrl == null || r2dbcUrl.isBlank()) {
            return null;
        }

        log.info("创建单数据源连接工厂，URL: {}, 数据库类型: {}", r2dbcUrl, databaseType);
        
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(r2dbcUrl);
        ConnectionFactoryOptions.Builder builder = ConnectionFactoryOptions.builder().from(options);
        
        // 根据数据库类型设置驱动名称
        if (databaseType != null && !databaseType.isBlank()) {
            builder.option(Option.valueOf("driver"), databaseType);
        }
        
        if (username != null && !username.isBlank()) {
            builder.option(USER, username);
        }
        if (password != null) {
            builder.option(PASSWORD, password);
        }
        
        ConnectionFactory connectionFactory;
        try {
            // 尝试使用 SPI 机制创建
            connectionFactory = ConnectionFactories.get(builder.build());
        } catch (Exception e) {
            log.warn("SPI机制创建连接工厂失败，尝试直接加载驱动类: {}", e.getMessage());
            // 直接使用驱动类创建
            connectionFactory = createConnectionFactoryByDriver(builder.build(), databaseType);
        }
        
        // 配置连接池
        WcdkSpringR2dbcProperties.Pool poolConfig = properties.getPool();
        if (poolConfig != null && poolConfig.isEnabled()) {
            ConnectionPoolConfiguration.Builder poolBuilder = ConnectionPoolConfiguration.builder(connectionFactory)
                    .name("primary")
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
            connectionFactory = new ConnectionPool(poolBuilder.build());
        }
        
        return connectionFactory;
    }

    /**
     * 根据数据库类型直接创建连接工厂
     */
    private ConnectionFactory createConnectionFactoryByDriver(ConnectionFactoryOptions options, String databaseType) {
        String driverClass;
        switch (databaseType.toLowerCase()) {
            case "dm":
                driverClass = "dm.r2dbc.DmConnectionFactoryProvider";
                break;
            case "postgresql":
                driverClass = "io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider";
                break;
            case "mysql":
                driverClass = "io.asyncer.r2dbc.mysql.MySqlConnectionFactoryProvider";
                break;
            case "oracle":
                driverClass = "io.r2dbc.oracle.OracleConnectionFactoryProvider";
                break;
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + databaseType);
        }
        
        try {
            Class<?> clazz = Class.forName(driverClass);
            Object provider = clazz.getDeclaredConstructor().newInstance();
            if (provider instanceof ConnectionFactoryProvider) {
                return ((ConnectionFactoryProvider) provider).create(options);
            }
            throw new IllegalStateException("驱动类不是 ConnectionFactoryProvider: " + driverClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("找不到数据库驱动类: " + driverClass + "，请确保已添加对应依赖", e);
        } catch (Exception e) {
            throw new IllegalStateException("创建连接工厂失败: " + databaseType, e);
        }
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
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnMissingBean(TransactionalAspect.class)
    @Role(ROLE_INFRASTRUCTURE)
    public TransactionalAspect transactionalAspect(TransactionalOperator transactionalOperator,
                                                    TransactionTemplate transactionTemplate) {
        return new TransactionalAspect(transactionalOperator, transactionTemplate);
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
                                                           RepositoryXmlRegistry repositoryXmlRegistry) {
        return new RepositoryProxyFactory(r2dbcUtil, properties, repositoryXmlRegistry);
    }

    @Bean
    public SqlLifecycleInterceptorInitializer sqlLifecycleInterceptorInitializer(
            ObjectProvider<List<SqlLifecycleInterceptor>> interceptorsProvider) {
        return new SqlLifecycleInterceptorInitializer(interceptorsProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "wcdk.r2dbc.database-initializer", name = "enabled", havingValue = "true")
    public DatabaseSchemaInitializer databaseSchemaInitializer(
            DatabaseClient databaseClient,
            ConnectionFactory connectionFactory,
            ResourcePatternResolver resourcePatternResolver,
            WcdkR2dbcProperties properties,
            @Value("${database.type:}") String databaseType) {
        return new DatabaseSchemaInitializer(databaseClient, connectionFactory, resourcePatternResolver, properties, databaseType);
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
