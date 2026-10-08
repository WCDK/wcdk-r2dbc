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
 **/
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
        this(repositoryOperations, properties, repositoryXmlRegistry, new SnowflakeIdGenerator());
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
        RepositoryMetadata metadata = entityClass != null ? new RepositoryMetadata(entityClass, properties,
                DatabaseDialects.get(repositoryOperations.databaseClient().getConnectionFactory())) : null;

        Map<java.lang.reflect.Method, RepositoryMethodPlan> methodPlans =
                new RepositoryMethodPlanCompiler(repositoryInterface, metadata, repositoryXmlRegistry, properties)
                        .compile();

        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(repositoryInterface);
        RepositoryInvocationDispatcher dispatcher = RepositoryInvocationDispatcherFactory.create(
                repositoryOperations, properties, metadata, repositoryInterface,
                repositoryXmlRegistry, snowflakeIdGenerator);
        methodPlans = dispatcher.bindPlans(methodPlans);
        if (repositoryOperations.databaseClient().getConnectionFactory()
                instanceof com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory routing) {
            // 表列引用及派生查询 SQL 都按目标数据库编译，避免仅分页切换方言。
            Map<String, Map<java.lang.reflect.Method, RepositoryMethodPlan>> routedPlans = new java.util.LinkedHashMap<>();
            routedPlans.put(routing.getPrimary(), methodPlans);
            for (var entry : routing.getConnectionFactories().entrySet()) {
                if (entry.getKey().equals(routing.getPrimary())) continue;
                var dialect = DatabaseDialects.get(entry.getValue());
                RepositoryMetadata targetMetadata = entityClass == null ? null
                        : new RepositoryMetadata(entityClass, properties, dialect);
                var targetDispatcher = RepositoryInvocationDispatcherFactory.create(repositoryOperations, properties,
                        targetMetadata, repositoryInterface, repositoryXmlRegistry, snowflakeIdGenerator, dialect);
                var targetPlans = new RepositoryMethodPlanCompiler(repositoryInterface, targetMetadata,
                        repositoryXmlRegistry, properties).compile();
                routedPlans.put(entry.getKey(), targetDispatcher.bindPlans(targetPlans));
            }
            dispatcher = new RepositoryInvocationDispatcher(routing, routedPlans);
        }
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
