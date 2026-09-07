package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceRouter;
import com.wcdk.r2dbc.execution.ParameterBinder;
import com.wcdk.r2dbc.execution.R2dbcRowMapper;
import com.wcdk.r2dbc.execution.SqlLifecycleExecutor;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import com.wcdk.r2dbc.transaction.TransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

/**
 * @auther WCDK
 *
 * @version 1.0
 **/
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WcdkR2dbcProperties.class, WcdkSpringR2dbcProperties.class})
@ConditionalOnProperty(prefix = "wcdk.r2dbc", name = "enabled", havingValue = "true")
public class R2dbcConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Role(ROLE_INFRASTRUCTURE)
    public R2dbcSqlLogger r2dbcSqlLogger(WcdkR2dbcProperties properties,
                                         WcdkSpringR2dbcProperties springR2dbcProperties) {
        return new R2dbcSqlLogger(properties, springR2dbcProperties);
    }

    @Bean
    @ConditionalOnBean(DatabaseClient.class)
    @ConditionalOnMissingBean
    @Role(ROLE_INFRASTRUCTURE)
    public R2dbcUtil r2dbcUtil(DatabaseClient databaseClient,
                               ObjectProvider<R2dbcEntityTemplate> entityTemplate,
                               ObjectProvider<TransactionalOperator> transactionalOperator,
                               WcdkR2dbcProperties properties,
                               WcdkSpringR2dbcProperties springR2dbcProperties,
                               ObjectProvider<TransactionManager> transactionManager,
                               ParameterBinder parameterBinder,
                               SqlLifecycleExecutor lifecycleExecutor,
                               R2dbcRowMapper rowMapper,
                               R2dbcSqlLogger sqlLogger,
                               R2dbcDataSourceRouter dataSourceRouter) {
        return new R2dbcUtil(databaseClient, entityTemplate.getIfAvailable(), transactionalOperator.getIfAvailable(),
                properties, springR2dbcProperties, transactionManager.getIfAvailable(), parameterBinder,
                lifecycleExecutor, rowMapper, sqlLogger, dataSourceRouter);
    }
}
