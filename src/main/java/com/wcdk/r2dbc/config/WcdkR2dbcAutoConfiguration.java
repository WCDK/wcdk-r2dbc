package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.repository.RepositoryOperations;
import com.wcdk.r2dbc.repository.RepositoryProxyFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceRouter;
import com.wcdk.r2dbc.execution.ParameterBinder;
import com.wcdk.r2dbc.dialect.DatabaseDialects;
import com.wcdk.r2dbc.execution.R2dbcRowMapper;
import com.wcdk.r2dbc.execution.R2dbcValueConverter;
import com.wcdk.r2dbc.execution.SqlLifecycleExecutor;
import com.wcdk.r2dbc.execution.SqlExecutionObserver;
import com.wcdk.r2dbc.execution.MicrometerSqlExecutionObserver;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptor;
import com.wcdk.r2dbc.execution.lifecycle.ReactiveSqlLifecycleInterceptor;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import com.wcdk.r2dbc.transaction.TransactionManager;
import com.wcdk.r2dbc.transaction.TransactionTemplate;
import com.wcdk.r2dbc.transaction.TransactionalAspect;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceAspect;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import io.micrometer.observation.ObservationRegistry;

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
public class WcdkR2dbcAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WcdkR2dbcAutoConfiguration.class);

    /**
     * 多数据源配置 - 动态路由连接工厂
     */
    @Bean
    @Conditional(WcdkR2dbcDataSourcesCondition.class)
    @ConditionalOnMissingBean(ConnectionFactory.class)
    @Role(ROLE_INFRASTRUCTURE)
    public DynamicRoutingConnectionFactory connectionFactory(WcdkSpringR2dbcProperties properties) {
        Map<String, WcdkSpringR2dbcProperties.DataSourceProperties> dataSources = properties.getDataSources();
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IllegalStateException("当Spring R2DBC ConnectionFactory缺失时，必须配置spring.r2dbc.data-sources");
        }
        if (!StringUtils.hasText(properties.getPrimary()) || !dataSources.containsKey(properties.getPrimary())) {
            throw new IllegalArgumentException("spring.r2dbc.primary='" + properties.getPrimary()
                    + "' 与配置的spring.r2dbc.data-sources键不匹配；可用键："
                    + dataSources.keySet());
        }
        Map<String, ConnectionFactory> connectionFactories = new LinkedHashMap<>();
        dataSources.forEach((name, dataSourceProperties) -> connectionFactories.put(name, createConnectionFactory(name, dataSourceProperties, properties.getPool())));
        return new DynamicRoutingConnectionFactory(properties.getPrimary(), connectionFactories);
    }

    /**
     * 单数据源配置 - 基于 spring.r2dbc.url 配置
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.r2dbc", name = "url")
    @ConditionalOnMissingBean(ConnectionFactory.class)
    @Role(ROLE_INFRASTRUCTURE)
    public ConnectionFactory singleConnectionFactory(
            WcdkSpringR2dbcProperties properties,
            @Value("${spring.r2dbc.url:}") String r2dbcUrl,
            @Value("${spring.r2dbc.username:}") String username,
            @Value("${spring.r2dbc.password:}") String password,
            @Value("${database.type:}") String databaseType) {
        
        if (r2dbcUrl == null || r2dbcUrl.isBlank()) {
            throw new IllegalArgumentException("spring.r2dbc.url不能为空");
        }
        if (properties.getPool() != null && properties.getPool().isEnabled()) {
            properties.getPool().validate("primary");
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
        
        try {
            ConnectionFactory connectionFactory = ConnectionFactories.get(builder.build());
            return poolConnectionFactory("primary", connectionFactory, properties.getPool());
        } catch (IllegalStateException e) {
            String driver = databaseType == null || databaseType.isBlank()
                    ? options.getRequiredValue(ConnectionFactoryOptions.DRIVER).toString()
                    : databaseType;
            throw missingDriver(driver, e);
        }
        
    }

    /**
     * 构造缺失 R2DBC SPI 驱动时的可操作错误信息。
     */
    private IllegalStateException missingDriver(String driver, RuntimeException cause) {
        String coordinates = switch (driver.toLowerCase()) {
            case "dm" -> "com.dameng:dm-r2dbc";
            case "postgres", "postgresql" -> "org.postgresql:r2dbc-postgresql";
            case "mysql" -> "io.asyncer:r2dbc-mysql";
            case "oracle" -> "com.oracle.database.r2dbc:oracle-r2dbc";
            default -> "an R2DBC driver that supports '" + driver + "'";
        };
        return new IllegalStateException("未找到数据库 '" + driver + "' 的R2DBC SPI提供者。请添加Maven依赖 " + coordinates + "。", cause);
    }

    @Bean
    @ConditionalOnMissingBean(DatabaseClient.class)
    @Role(ROLE_INFRASTRUCTURE)
    public DatabaseClient databaseClient(ConnectionFactory connectionFactory) {
        return DatabaseClient.create(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "connectionFactoryDisposer")
    @Role(ROLE_INFRASTRUCTURE)
    public DisposableBean connectionFactoryDisposer(ConnectionFactory connectionFactory) {
        return () -> {
            if (connectionFactory instanceof Disposable disposable) {
                disposable.dispose();
            }
        };
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
    @ConditionalOnMissingBean(R2dbcUtil.class)
    @Role(ROLE_INFRASTRUCTURE)
    public R2dbcUtil r2dbcUtil(DatabaseClient databaseClient,
                               ObjectProvider<R2dbcEntityTemplate> entityTemplate,
                               ObjectProvider<TransactionalOperator> transactionalOperator,
                               WcdkR2dbcProperties properties,
                               WcdkSpringR2dbcProperties springR2dbcProperties,
                               ObjectProvider<TransactionManager> transactionManagerProvider,
                               ParameterBinder parameterBinder,
                               SqlLifecycleExecutor lifecycleExecutor,
                               R2dbcRowMapper rowMapper,
                               R2dbcSqlLogger sqlLogger,
                               R2dbcDataSourceRouter dataSourceRouter) {
        return new R2dbcUtil(databaseClient, entityTemplate.getIfAvailable(), transactionalOperator.getIfAvailable(),
                properties, springR2dbcProperties, transactionManagerProvider.getIfAvailable(),
                parameterBinder, lifecycleExecutor, rowMapper, sqlLogger, dataSourceRouter);
    }

    @Bean
    @ConditionalOnMissingBean
    public ParameterBinder parameterBinder(ConnectionFactory connectionFactory) {
        return new ParameterBinder(DatabaseDialects.get(connectionFactory));
    }

    @Bean
    @ConditionalOnMissingBean
    public R2dbcRowMapper r2dbcRowMapper(ObjectProvider<R2dbcValueConverter> converters) {
        return new R2dbcRowMapper(converters.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public R2dbcSqlLogger r2dbcSqlLogger(WcdkR2dbcProperties properties,
                                         WcdkSpringR2dbcProperties springProperties) {
        return new R2dbcSqlLogger(properties, springProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public R2dbcDataSourceRouter r2dbcDataSourceRouter() {
        return new R2dbcDataSourceRouter();
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlLifecycleExecutor sqlLifecycleExecutor(SqlLifecycleInterceptorChain chain,
                                                       SqlExecutionObserver observer) {
        return new SqlLifecycleExecutor(chain, observer);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlExecutionObserver sqlExecutionObserver(WcdkR2dbcProperties properties,
                                                       ObjectProvider<ObservationRegistry> registry) {
        ObservationRegistry available = registry.getIfAvailable();
        return properties.isObservabilityEnabled() && available != null
                ? new MicrometerSqlExecutionObserver(available)
                : SqlExecutionObserver.NOOP;
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
    @ConditionalOnProperty(prefix = "wcdk.r2dbc.transaction", name = "aspect-enabled", havingValue = "true")
    @ConditionalOnMissingBean(value = TransactionalAspect.class, name = "org.springframework.transaction.config.internalTransactionAdvisor")
    @Role(ROLE_INFRASTRUCTURE)
    public TransactionalAspect transactionalAspect(ReactiveTransactionManager transactionManager) {
        return new TransactionalAspect(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(RepositoryXmlRegistry.class)
    @Role(ROLE_INFRASTRUCTURE)
    public RepositoryXmlRegistry mapperXmlRegistry(ResourcePatternResolver resourcePatternResolver, WcdkR2dbcProperties properties) {
        return new RepositoryXmlRegistry(resourcePatternResolver, properties);
    }

    @Bean
    @ConditionalOnMissingBean(RepositoryOperations.class)
    public RepositoryOperations repositoryOperations(R2dbcUtil r2dbcUtil) {
        return new com.wcdk.r2dbc.R2dbcRepositoryOperations(r2dbcUtil);
    }

    @Bean
    @ConditionalOnMissingBean(RepositoryProxyFactory.class)
    @Role(ROLE_INFRASTRUCTURE)
    public RepositoryProxyFactory repositoryProxyFactory(RepositoryOperations repositoryOperations,
                                                           WcdkR2dbcProperties properties,
                                                           RepositoryXmlRegistry repositoryXmlRegistry) {
        return new RepositoryProxyFactory(repositoryOperations, properties, repositoryXmlRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(SqlLifecycleInterceptorChain.class)
    public SqlLifecycleInterceptorChain sqlLifecycleInterceptorChain(
            ObjectProvider<SqlLifecycleInterceptor> syncProvider,
            ObjectProvider<ReactiveSqlLifecycleInterceptor> reactiveProvider) {
        List<SqlLifecycleInterceptor> sync = syncProvider.orderedStream().toList();
        List<ReactiveSqlLifecycleInterceptor> reactive = reactiveProvider.orderedStream().toList();
        log.info("初始化SQL生命周期拦截器链: reactive={}, sync={}",
                reactive.stream().map(i -> i.getClass().getName()).toList(),
                sync.stream().map(i -> i.getClass().getName()).toList());
        return new SqlLifecycleInterceptorChain(sync, reactive);
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
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("spring.r2dbc.data-sources包含空的数据源名称");
        }
        if (dsProperties == null || dsProperties.getUrl() == null || dsProperties.getUrl().isBlank()) {
            throw new IllegalArgumentException("spring.r2dbc.data-sources." + name + ".url不能为空");
        }
        WcdkSpringR2dbcProperties.Pool poolConfig = dsProperties.effectivePool(globalPool);
        if (poolConfig != null && poolConfig.isEnabled()) {
            poolConfig.validate(name);
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
        ConnectionFactory connectionFactory;
        try {
            connectionFactory = ConnectionFactories.get(builder.build());
        } catch (IllegalStateException e) {
            Object driver = builder.build().getRequiredValue(ConnectionFactoryOptions.DRIVER);
            throw missingDriver(driver.toString(), e);
        }
        return poolConnectionFactory(name, connectionFactory, dsProperties, globalPool);
    }

    private ConnectionFactory poolConnectionFactory(String name, ConnectionFactory connectionFactory, WcdkSpringR2dbcProperties.DataSourceProperties dsProperties, WcdkSpringR2dbcProperties.Pool globalPool) {
        WcdkSpringR2dbcProperties.Pool poolConfig = dsProperties.effectivePool(globalPool);
        return poolConnectionFactory(name, connectionFactory, poolConfig);
    }

    private ConnectionFactory poolConnectionFactory(String name, ConnectionFactory connectionFactory, WcdkSpringR2dbcProperties.Pool poolConfig) {
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
