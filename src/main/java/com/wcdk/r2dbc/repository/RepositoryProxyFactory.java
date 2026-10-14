package com.wcdk.r2dbc.repository;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import com.wcdk.r2dbc.execution.RepositoryOperations;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.dialect.DatabaseDialects;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import com.wcdk.r2dbc.id.SnowflakeIdGenerator;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.ResolvableType;

import java.util.Map;

/**
 * 仓储代理工厂。
 *
 * @author WCDK
 *
 * @version 1.0
 */
public class RepositoryProxyFactory {

    private final RepositoryOperations repositoryOperations;

    private final WcdkR2dbcProperties properties;

    private final RepositoryXmlRegistry repositoryXmlRegistry;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 兼容直接构造仓储代理工厂的旧调用方式。
     *
     * @author wcdk
     */
    public RepositoryProxyFactory(RepositoryOperations repositoryOperations, WcdkR2dbcProperties properties,
                                  RepositoryXmlRegistry repositoryXmlRegistry) {
        this(repositoryOperations, properties, repositoryXmlRegistry, properties.isSnowflakeId()
                ? (properties.getSnowflakeWorkerId() == null ? new SnowflakeIdGenerator()
                : new SnowflakeIdGenerator(properties.getSnowflakeWorkerId())) : null);
    }

    /**
     * 创建仓储代理工厂并复用容器中的雪花 ID 生成器。
     *
     * @author wcdk
     */
    public RepositoryProxyFactory(RepositoryOperations repositoryOperations, WcdkR2dbcProperties properties,
                                  RepositoryXmlRegistry repositoryXmlRegistry,
                                  SnowflakeIdGenerator snowflakeIdGenerator) {
        this.repositoryOperations = repositoryOperations;
        this.properties = properties;
        this.repositoryXmlRegistry = repositoryXmlRegistry;
        this.snowflakeIdGenerator = properties.isSnowflakeId() ? snowflakeIdGenerator : null;
    }

    public Object create(Class<?> repositoryInterface) {
        Class<?> entityClass = resolveEntityClass(repositoryInterface);
        var connectionFactory = repositoryOperations.databaseClient().getConnectionFactory();
        Map<java.lang.reflect.Method, RepositoryMethodPlan> methodPlans;
        RepositoryInvocationDispatcher dispatcher;
        if (connectionFactory instanceof com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory routing) {
            Map<String, Map<java.lang.reflect.Method, RepositoryMethodPlan>> routedPlans = new java.util.LinkedHashMap<>();
            for (var entry : routing.getConnectionFactories().entrySet()) {
                routedPlans.put(entry.getKey(), compileBoundPlans(repositoryInterface, entityClass, entry.getValue()));
            }
            methodPlans = routedPlans.get(routing.getPrimary());
            dispatcher = new RepositoryInvocationDispatcher(routing, routedPlans);
        } else {
            var dialect = DatabaseDialects.get(connectionFactory);
            RepositoryMetadata metadata = entityClass == null ? null : new RepositoryMetadata(entityClass, properties, dialect);
            dispatcher = RepositoryInvocationDispatcherFactory.create(repositoryOperations, properties, metadata,
                    repositoryInterface, repositoryXmlRegistry, snowflakeIdGenerator, dialect);
            methodPlans = dispatcher.bindPlans(compilePlans(repositoryInterface, metadata));
        }
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(repositoryInterface);
        proxyFactory.addAdvice(new RepositoryProxyMethodInterceptor(methodPlans, dispatcher));
        return proxyFactory.getProxy(repositoryInterface.getClassLoader());
    }

    private Map<java.lang.reflect.Method, RepositoryMethodPlan> compileBoundPlans(Class<?> repositoryInterface, Class<?> entityClass,
                                                                               io.r2dbc.spi.ConnectionFactory connectionFactory) {
        var dialect = DatabaseDialects.get(connectionFactory);
        RepositoryMetadata metadata = entityClass == null ? null : new RepositoryMetadata(entityClass, properties, dialect);
        var dispatcher = RepositoryInvocationDispatcherFactory.create(repositoryOperations, properties, metadata,
                repositoryInterface, repositoryXmlRegistry, snowflakeIdGenerator, dialect);
        return dispatcher.bindPlans(compilePlans(repositoryInterface, metadata));
    }

    Map<java.lang.reflect.Method, RepositoryMethodPlan> compilePlans(Class<?> repositoryInterface, RepositoryMetadata metadata) {
        return new RepositoryMethodPlanCompiler(repositoryInterface, metadata, repositoryXmlRegistry, properties).compile();
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
