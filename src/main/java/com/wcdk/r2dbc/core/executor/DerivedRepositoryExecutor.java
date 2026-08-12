package com.wcdk.r2dbc.core;
import com.wcdk.r2dbc.core.plan.RepositoryMethodPlan;

import com.wcdk.r2dbc.R2dbcUtil;
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

/***
 * Derived Query 执行器。
 * @author wcdk
 */
final class DerivedRepositoryExecutor implements RepositoryMethodExecutor {
    private final WcdkR2dbcProperties properties;
    private final RepositoryMetadata metadata;
    private final Class<?> repositoryInterface;
    private final CustomMethodResolver customMethodResolver;
    private final SqlExecutionEngine sqlExecutionEngine;

    DerivedRepositoryExecutor(WcdkR2dbcProperties properties, RepositoryMetadata metadata, Class<?> repositoryInterface, SqlExecutionEngine sqlExecutionEngine) {
        this.properties = properties;
        this.metadata = metadata;
        this.repositoryInterface = repositoryInterface;
        this.sqlExecutionEngine = sqlExecutionEngine;
        this.customMethodResolver = metadata == null ? null : new CustomMethodResolver(metadata, properties.getLogicDeleteValue(), properties.getLogicNotDeleteValue());
    }

    private Class<?> reactiveValueType(Method method) {
        org.springframework.core.ResolvableType type = org.springframework.core.ResolvableType.forMethodReturnType(method);
        if (method.getReturnType() == Mono.class || method.getReturnType() == Flux.class) {
            Class<?> resolved = type.getGeneric(0).resolve();
            return resolved == null ? Object.class : resolved;
        }
        return method.getReturnType();
    }

    private Number numberValue(Row row) {
        Object value = row.get(0);
        if (!(value instanceof Number number)) throw new IllegalStateException("查询结果不是数字: " + value);
        return number;
    }

    private SqlLifecycleExecutor lifecycleExecutor() {
        return sqlExecutionEngine.lifecycleExecutor();
    }

    private Method findMethod(String name) {
        for (Method method : repositoryInterface.getMethods()) if (method.getName().equals(name)) return method;
        throw new IllegalStateException("Repository方法不存在: " + name);
    }

        @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.DERIVED;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy) {
        return executeCustomMethod(plan, args);
    }
Object executeCustomMethod(RepositoryMethodPlan plan, Object[] arguments) {
        Method method = plan.method();
        if (metadata == null) {
            throw new UnsupportedOperationException("仓储接口未继承 BaseRepository，不支持自定义方法：" + method.getName());
        }

        Object compiled = plan.sqlPlan().compiledPlan();
        if (!(compiled instanceof DerivedQueryDefinition derivedPlan)) {
            throw new IllegalStateException("派生查询未在启动阶段编译: " + method.toGenericString());
        }
        CustomMethodResolver.ParsedMethod parsedMethod = customMethodResolver.resolve(derivedPlan, method, arguments);
        if (plan.sqlPlan().template() != null) {
            parsedMethod = new CustomMethodResolver.ParsedMethod(plan.sqlPlan().template(),
                    parsedMethod.parameters(), parsedMethod.commandType());
        }

        if (parsedMethod == null) {
            throw new UnsupportedOperationException("暂不支持自定义仓储方法：" + method.getName());
        }

        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(method, repositoryInterface, arguments);
        context.setSql(parsedMethod.sql());
        context.setParameters(parsedMethod.parameters());
        context.setCommandType(parsedMethod.commandType() == CustomMethodResolver.SqlCommandType.SELECT
                ? com.wcdk.r2dbc.core.xml.SqlCommandType.SELECT
                : com.wcdk.r2dbc.core.xml.SqlCommandType.UPDATE);

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context, Mono::empty);

        boolean returnsFlux = method.getReturnType() == Flux.class;
        Class<?> valueType = reactiveValueType(method);

        if (parsedMethod.commandType() == CustomMethodResolver.SqlCommandType.SELECT) {
            if (valueType == Long.class || valueType == long.class) {
                return executeCustomCountQuery(lifecycle, context, chain);
            } else if (valueType == Boolean.class || valueType == boolean.class) {
                return executeCustomExistsQuery(lifecycle, context, chain);
            } else if (returnsFlux) {
                return executeCustomFindQueryFlux(lifecycle, context, chain);
            } else {
                return executeCustomFindQueryMono(lifecycle, context, chain);
            }
        } else {
            return executeCustomUpdate(lifecycle, method, valueType, context, chain);
        }
    }

    private Object executeCustomCountQuery(Mono<Boolean> lifecycle, SqlExecutionContext context,
                                           SqlLifecycleInterceptorChain chain) {
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                                (row, rowMetadata) -> numberValue(row).longValue())
                        .defaultIfEmpty(0L));
    }

    private Object executeCustomExistsQuery(Mono<Boolean> lifecycle, SqlExecutionContext context,
                                            SqlLifecycleInterceptorChain chain) {
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                                (row, rowMetadata) -> numberValue(row).longValue() > 0)
                        .defaultIfEmpty(false));
    }

    private Object executeCustomFindQueryFlux(Mono<Boolean> lifecycle, SqlExecutionContext context,
                                              SqlLifecycleInterceptorChain chain) {
        return lifecycleExecutor().executeFlux(chain, context, lifecycle,
                () -> sqlExecutionEngine.queryWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> sqlExecutionEngine.map(row, metadata.entityClass())));
    }

    private Object executeCustomFindQueryMono(Mono<Boolean> lifecycle, SqlExecutionContext context,
                                              SqlLifecycleInterceptorChain chain) {
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> sqlExecutionEngine.map(row, metadata.entityClass())));
    }

    private Object executeCustomUpdate(Mono<Boolean> lifecycle, Method method, Class<?> valueType,
                                       SqlExecutionContext context, SqlLifecycleInterceptorChain chain) {
        Mono<Long> updateMono = lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.updateWithoutLifecycle(context.getSql(), context.getParameters()));

        if (method.getReturnType() == Mono.class && valueType == Boolean.class) {
            return updateMono.map(count -> count > 0);
        }
        if (method.getReturnType() == Mono.class && (valueType == Integer.class || valueType == int.class)) {
            return updateMono.map(Math::toIntExact);
        }
        if (method.getReturnType() == Mono.class && valueType == Void.class) {
            return updateMono.then();
        }
        return updateMono;
    }


}
