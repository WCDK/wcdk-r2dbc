package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.execution.RepositoryOperations;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import com.wcdk.r2dbc.id.SnowflakeIdGenerator;
import com.wcdk.r2dbc.dialect.DatabaseDialects;
import com.wcdk.r2dbc.dialect.DatabaseDialect;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/***
 * Repository 方法执行器组装器。
 *
 * @author wcdk
 **/
final class RepositoryInvocationDispatcherFactory {

    private RepositoryInvocationDispatcherFactory() {
    }

    /***
     * 创建 Repository 方法分发器及其执行器。
     *
     * @param repositoryOperations 数据库操作
     * @param properties           R2DBC 配置
     * @param metadata             Repository 元数据
     * @param repositoryInterface  Repository 接口
     * @param repositoryXmlRegistry XML 注册表
     * @param snowflakeIdGenerator 雪花 ID 生成器
     * @return 方法分发器
     * @author wcdk
     **/
    static RepositoryInvocationDispatcher create(RepositoryOperations repositoryOperations,
                                                  WcdkR2dbcProperties properties,
                                                  RepositoryMetadata metadata,
                                                  Class<?> repositoryInterface,
                                                  RepositoryXmlRegistry repositoryXmlRegistry,
                                                  SnowflakeIdGenerator snowflakeIdGenerator) {
        SqlExecutionEngine sqlExecutionEngine = new SqlExecutionEngine(repositoryOperations);
        DatabaseDialect dialect = DatabaseDialects.get(sqlExecutionEngine.connectionFactory());
        RepositoryParameterBinder parameterBinder = new RepositoryParameterBinder();
        CrudRepositoryExecutor crudExecutor = new CrudRepositoryExecutor(
                properties, metadata, repositoryInterface, repositoryXmlRegistry,
                dialect, snowflakeIdGenerator, sqlExecutionEngine, parameterBinder);
        DerivedRepositoryExecutor derivedExecutor = new DerivedRepositoryExecutor(
                properties, metadata, repositoryInterface, sqlExecutionEngine);
        XmlRepositoryExecutor xmlExecutor = new XmlRepositoryExecutor(
                metadata, repositoryInterface, repositoryXmlRegistry,
                sqlExecutionEngine, parameterBinder);
        ObjectMethodExecutor objectExecutor =
                new ObjectMethodExecutor(repositoryInterface, metadata);
        return new RepositoryInvocationDispatcher(List.of(
                new QuerySpecExecutor(crudExecutor),
                crudExecutor,
                derivedExecutor,
                xmlExecutor,
                objectExecutor,
                new UnsupportedMethodExecutor()));
    }
}
