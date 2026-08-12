package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.executor.SqlLifecycleExecutor;
import com.wcdk.r2dbc.core.executor.SqlParameter;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.id.SnowflakeIdGenerator;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata.FieldColumn;
import com.wcdk.r2dbc.core.query.QueryWrapper;
import com.wcdk.r2dbc.core.xml.DynamicSqlSource;
import com.wcdk.r2dbc.core.xml.ResultMapDefinition;
import com.wcdk.r2dbc.core.xml.RepositoryStatement;
import com.wcdk.r2dbc.core.xml.RepositoryXmlRegistry;
import io.r2dbc.spi.Row;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;
import org.springframework.data.repository.query.Param;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基础仓储方法拦截器。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
class RepositoryProxyMethodInterceptor implements MethodInterceptor {

    private static final Pattern PARAMETER_PATTERN = Pattern.compile("#\\{\\s*([a-zA-Z0-9_.$]+)\\s*}");

    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    
    private final WcdkR2dbcProperties properties;

    private final RepositoryMetadata metadata;

    private final Class<?> repositoryInterface;

    private final RepositoryXmlRegistry repositoryXmlRegistry;

    private final R2dbcDialect dialect;

    private final RepositoryOperations repositoryOperations;

    private final SqlExecutionEngine sqlExecutionEngine;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    private final boolean snowflakeIdEnabled;

    private final CustomMethodResolver customMethodResolver;

    private final RepositoryMethodPlanRegistry planRegistry;

    private final RepositoryInvocationDispatcher dispatcher;

    private final RepositoryObjectMethodExecutor objectMethodExecutor;

    private final RepositoryParameterBinder parameterBinder;

    private final CrudRepositoryExecutor crudRepositoryExecutor;
    private final DerivedQueryExecutor derivedQueryExecutor;
    private final XmlRepositoryExecutor xmlRepositoryExecutor;

    RepositoryProxyMethodInterceptor(RepositoryOperations repositoryOperations,
                                     WcdkR2dbcProperties properties,
                                     RepositoryMetadata metadata,
                                     Class<?> repositoryInterface,
                                     RepositoryXmlRegistry repositoryXmlRegistry,
                                     SnowflakeIdGenerator snowflakeIdGenerator,
                                     Map<Method, RepositoryMethodPlan> methodPlans) {
        this.repositoryOperations = repositoryOperations;
        this.sqlExecutionEngine = new SqlExecutionEngine(repositoryOperations);
        this.properties = properties;
        this.metadata = metadata;
        this.repositoryInterface = repositoryInterface;
        this.repositoryXmlRegistry = repositoryXmlRegistry;
        this.dialect = DialectResolver.getDialect(sqlExecutionEngine.connectionFactory());
        this.snowflakeIdEnabled = properties.isSnowflakeId();
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.customMethodResolver = metadata == null ? null : new CustomMethodResolver(metadata,
                properties.getLogicDeleteValue(), properties.getLogicNotDeleteValue());
        this.planRegistry = new RepositoryMethodPlanRegistry(methodPlans);
        this.objectMethodExecutor = new RepositoryObjectMethodExecutor(repositoryInterface, metadata);
        this.parameterBinder = new RepositoryParameterBinder();
        this.crudRepositoryExecutor = new CrudRepositoryExecutor(properties, metadata, repositoryInterface, repositoryXmlRegistry, dialect, snowflakeIdGenerator, sqlExecutionEngine, parameterBinder);
        this.derivedQueryExecutor = new DerivedQueryExecutor(properties, metadata, repositoryInterface, sqlExecutionEngine);
        this.xmlRepositoryExecutor = new XmlRepositoryExecutor(metadata, repositoryInterface, repositoryXmlRegistry, sqlExecutionEngine, parameterBinder);
        this.dispatcher = new RepositoryInvocationDispatcher(List.of(
                new CrudMethodExecutor((plan, args, context, proxy) -> crudRepositoryExecutor.executeCrudPlan(plan, args, context)),
                new DerivedMethodExecutor((plan, args, context, proxy) -> derivedQueryExecutor.executeCustomMethod(plan, args)),
                new XmlMethodExecutor((plan, args, context, proxy) -> xmlRepositoryExecutor.executeXmlStatement(plan.xmlStatement(), plan.method(), args)),
                objectMethodExecutor,
                new UnsupportedMethodExecutor()));
    }

    @Override
    public Object invoke(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        RepositoryPlan typedPlan = planRegistry.get(method);
        RepositoryMethodPlan plan = typedPlan == null ? null : typedPlan.legacy();
        if (plan == null) {
            throw new IllegalStateException("Repository方法未在启动时编译: " + method);
        }
        if (method.getReturnType() == Mono.class) {
            return Mono.deferContextual(context -> {
                Object result = invokeOnce(typedPlan, invocation.getThis(), invocation.getArguments(), context);
                return result instanceof Mono<?> mono ? mono : Mono.justOrEmpty(result);
            });
        }
        if (method.getReturnType() == Flux.class) {
            return Flux.deferContextual(context -> {
                Object result = invokeOnce(typedPlan, invocation.getThis(), invocation.getArguments(), context);
                if (result instanceof org.reactivestreams.Publisher<?> publisher) {
                    @SuppressWarnings("unchecked")
                    org.reactivestreams.Publisher<Object> typed =
                            (org.reactivestreams.Publisher<Object>) publisher;
                    return Flux.from(typed);
                }
                return Flux.just(result);
            });
        }
        return invokeOnce(typedPlan, invocation.getThis(), invocation.getArguments(), reactor.util.context.Context.empty());
    }

    private Object invokeOnce(RepositoryPlan plan, Object proxy, Object[] arguments, ContextView context) {
        return dispatcher.execute(plan, arguments, context, proxy);
    }

    static Object terminatedPublisher(Method method) {
        return method.getReturnType() == Flux.class ? Flux.empty() : Mono.empty();
    }


}
