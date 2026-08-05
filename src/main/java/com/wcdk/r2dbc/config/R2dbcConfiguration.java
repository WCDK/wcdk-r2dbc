package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.R2dbcUtil;
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
 * @date 2026/7/20
 * @version 1.0
 **/
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WcdkR2dbcProperties.class)
@ConditionalOnProperty(prefix = "wcdk.r2dbc", name = "enabled", havingValue = "true")
public class R2dbcConfiguration {

    @Bean
    @ConditionalOnBean(DatabaseClient.class)
    @ConditionalOnMissingBean
    @Role(ROLE_INFRASTRUCTURE)
    public R2dbcUtil r2dbcUtil(DatabaseClient databaseClient,
                               ObjectProvider<R2dbcEntityTemplate> entityTemplate,
                               ObjectProvider<TransactionalOperator> transactionalOperator,
                               WcdkR2dbcProperties properties) {
        return new R2dbcUtil(databaseClient, entityTemplate.getIfAvailable(), transactionalOperator.getIfAvailable(), properties);
    }
}
