package com.wcdk.r2dbc.repository;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import com.wcdk.r2dbc.id.SnowflakeIdGenerator;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.ResolvableType;

import java.util.Map;

/**
 * 仓储代理工厂。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public class RepositoryProxyFactory {

    private final RepositoryOperations repositoryOperations;

    private final WcdkR2dbcProperties properties;

    private final RepositoryXmlRegistry repositoryXmlRegistry;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public RepositoryProxyFactory(RepositoryOperations repositoryOperations, WcdkR2dbcProperties properties, RepositoryXmlRegistry repositoryXmlRegistry) {
        this.repositoryOperations = repositoryOperations;
        this.properties = properties;
        this.repositoryXmlRegistry = repositoryXmlRegistry;
        this.snowflakeIdGenerator = properties.isSnowflakeId() ? new SnowflakeIdGenerator() : null;
    }

    public Object create(Class<?> repositoryInterface) {
        Class<?> entityClass = resolveEntityClass(repositoryInterface);
        RepositoryMetadata metadata = entityClass != null ? new RepositoryMetadata(entityClass, properties) : null;

        Map<java.lang.reflect.Method, RepositoryMethodPlan> methodPlans =
                new RepositoryMethodPlanCompiler(repositoryInterface, metadata, repositoryXmlRegistry, properties)
                        .compile();

        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(repositoryInterface);
        RepositoryInvocationDispatcher dispatcher = RepositoryInvocationDispatcherFactory.create(
                repositoryOperations, properties, metadata, repositoryInterface,
                repositoryXmlRegistry, snowflakeIdGenerator);
        methodPlans = dispatcher.bindPlans(methodPlans);
        proxyFactory.addAdvice(new RepositoryProxyMethodInterceptor(methodPlans, dispatcher));
        return proxyFactory.getProxy(repositoryInterface.getClassLoader());
    }

    private Class<?> resolveEntityClass(Class<?> repositoryInterface) {
        ResolvableType repositoryType = ResolvableType.forClass(repositoryInterface).as(BaseRepository.class);
        if (repositoryType == ResolvableType.NONE) {
            return null;
        }
        Class<?> entityClass = repositoryType.getGeneric(0).resolve();
        if (entityClass == null) {
            throw new IllegalStateException("无法解析仓储实体类型: " + repositoryInterface.getName());
        }
        return entityClass;
    }
}
