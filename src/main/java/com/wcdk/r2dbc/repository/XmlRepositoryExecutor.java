package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.SqlLifecycleExecutor;
import com.wcdk.r2dbc.execution.SqlParameter;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.id.SnowflakeIdGenerator;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata.FieldColumn;
import com.wcdk.r2dbc.query.QueryWrapper;
import com.wcdk.r2dbc.query.xml.DynamicSqlSource;
import com.wcdk.r2dbc.query.xml.ResultMapDefinition;
import com.wcdk.r2dbc.query.xml.RepositoryStatement;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import io.r2dbc.spi.Row;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import com.wcdk.r2dbc.dialect.DatabaseDialects;
import com.wcdk.r2dbc.dialect.DatabaseDialect;
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
 * XML Repository 执行器。
 * @author wcdk
 */
final class XmlRepositoryExecutor implements RepositoryMethodExecutor {
    private final RepositoryMetadata metadata;
    private final Class<?> repositoryInterface;
    private final RepositoryXmlRegistry repositoryXmlRegistry;
    private final SqlExecutionEngine sqlExecutionEngine;
    private final RepositoryParameterBinder parameterBinder;
    XmlRepositoryExecutor(RepositoryMetadata metadata, Class<?> repositoryInterface, RepositoryXmlRegistry repositoryXmlRegistry, SqlExecutionEngine sqlExecutionEngine, RepositoryParameterBinder parameterBinder) {
        this.metadata = metadata; this.repositoryInterface = repositoryInterface; this.repositoryXmlRegistry = repositoryXmlRegistry; this.sqlExecutionEngine = sqlExecutionEngine; this.parameterBinder = parameterBinder;
    }
    private SqlLifecycleExecutor lifecycleExecutor() { return sqlExecutionEngine.lifecycleExecutor(); }
    private Method findMethod(String name) { for (Method method : repositoryInterface.getMethods()) if (method.getName().equals(name)) return method; throw new IllegalStateException("Repository方法不存在: " + name); }
        @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.XML;
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy) {
        return executeXmlStatement(plan.xmlStatement(), plan.method(), args);
    }
Object executeXmlStatement(RepositoryStatement statement, Method method, Object[] arguments) {
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(method, repositoryInterface, arguments);
        context.setParameters(parameterBinder.methodParameters(method, arguments));
        context.setCommandType(statement.commandType());

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    DynamicSqlSource.RenderedSql renderedSql = statement.render(context.getParameters());
                    Map<String, Object> sourceParameters = new LinkedHashMap<>(context.getParameters());
                    sourceParameters.putAll(renderedSql.additionalParameters());
                    RepositoryParameterBinder.BoundSql boundSql = parameterBinder.bindSql(renderedSql.sql(), method, arguments, sourceParameters);
                    context.setSql(boundSql.sql());
                    context.setParameters(boundSql.parameters());
                }));

        boolean returnsFlux = method.getReturnType() == Flux.class;

        if (returnsFlux) {
            return lifecycleExecutor().executeFlux(chain, context, lifecycle,
                    () -> Flux.defer(() -> {
                        RepositoryParameterBinder.BoundSql finalBoundSql = new RepositoryParameterBinder.BoundSql(context.getSql(), context.getParameters());

                        Object result;
                        try {
                            result = switch (statement.commandType()) {
                                case INSERT, UPDATE, DELETE, MERGE -> executeXmlUpdate(finalBoundSql, method, arguments);
                                case SELECT -> executeXmlSelect(finalBoundSql, method, statement);
                                case UNKNOWN -> throw new IllegalStateException("未知的XML SQL命令类型");
                            };
                        } catch (Exception e) {
                            return Flux.error(e);
                        }

                        @SuppressWarnings("unchecked")
                        Flux<Object> flux = result instanceof Flux<?> f
                                ? (Flux<Object>) f
                                : result instanceof Mono<?> m
                                        ? (Flux<Object>) m.flux()
                                        : Flux.just(result);
                        return flux;
                    }));
        } else {
            return lifecycleExecutor().executeMono(chain, context, lifecycle,
                    () -> Mono.defer(() -> {
                        RepositoryParameterBinder.BoundSql finalBoundSql = new RepositoryParameterBinder.BoundSql(context.getSql(), context.getParameters());

                        Object result;
                        try {
                            result = switch (statement.commandType()) {
                                case INSERT, UPDATE, DELETE, MERGE -> executeXmlUpdate(finalBoundSql, method, arguments);
                                case SELECT -> executeXmlSelect(finalBoundSql, method, statement);
                                case UNKNOWN -> throw new IllegalStateException("未知的XML SQL命令类型");
                            };
                        } catch (Exception e) {
                            return Mono.error(e);
                        }

                        if (result instanceof Mono<?> mono) {
                            return mono;
                        } else if (result instanceof Flux<?> flux) {
                            return flux.singleOrEmpty();
                        }
                        return Mono.justOrEmpty(result);
                    }));
        }
    }

    private Object executeXmlUpdate(RepositoryParameterBinder.BoundSql boundSql, Method method, Object[] arguments) {
        Mono<Long> rows = sqlExecutionEngine.updateWithoutLifecycle(boundSql.sql(), boundSql.parameters());
        Class<?> valueType = reactiveValueType(method);
        if (method.getReturnType() == Mono.class && valueType == Boolean.class) {
            return rows.map(count -> count > 0);
        }
        if (method.getReturnType() == Mono.class && metadata != null && metadata.entityClass().isAssignableFrom(valueType)) {
            Object entity = arguments == null || arguments.length == 0 ? null : arguments[0];
            return rows.thenReturn(entity);
        }
        return rows;
    }

    private Object executeXmlSelect(RepositoryParameterBinder.BoundSql boundSql, Method method, RepositoryStatement statement) {
        Class<?> valueType = reactiveValueType(method);
        String resultType = statement.resultType();
        String resultMapId = statement.resultMapId();

        if (method.getReturnType() == Flux.class) {
            return sqlExecutionEngine.queryWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) ->
                    mapXmlRow(row, valueType, resultType, resultMapId));
        }
        if (valueType == Boolean.class || valueType == boolean.class) {
            return sqlExecutionEngine.queryOneWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) -> numberValue(row).longValue() > 0)
                    .defaultIfEmpty(false);
        }
        if (valueType == Long.class || valueType == long.class) {
            return sqlExecutionEngine.queryOneWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) -> numberValue(row).longValue())
                    .defaultIfEmpty(0L);
        }
        if (valueType == Integer.class || valueType == int.class) {
            return sqlExecutionEngine.queryOneWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) -> numberValue(row).intValue())
                    .defaultIfEmpty(0);
        }
        return sqlExecutionEngine.queryOneWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) ->
                mapXmlRow(row, valueType, resultType, resultMapId));
    }

    private Object mapXmlRow(Row row, Class<?> valueType, String resultType, String resultMapId) {
        if (StringUtils.hasText(resultMapId)) {
            return mapRowByResultMap(row, resultMapId);
        }
        if (StringUtils.hasText(resultType)) {
            Class<?> targetClass = resolveClass(resultType);
            if (Number.class.isAssignableFrom(targetClass) || targetClass == String.class || targetClass == Boolean.class || targetClass == boolean.class) {
                return sqlExecutionEngine.convertValue(row.get(0), targetClass);
            }
            return sqlExecutionEngine.map(row, targetClass);
        }
        if (valueType == Object.class) {
            if (metadata == null) {
                throw new IllegalStateException("无法确定实体类型，请在 XML 中明确指定返回值类型或继承 BaseRepository");
            }
            return sqlExecutionEngine.map(row, metadata.entityClass());
        }
        if (Number.class.isAssignableFrom(valueType) || valueType == String.class || valueType == Boolean.class || valueType == boolean.class) {
            return sqlExecutionEngine.convertValue(row.get(0), valueType);
        }
        return sqlExecutionEngine.map(row, valueType);
    }

    private Object mapRowByResultMap(Row row, String resultMapId) {
        ResultMapDefinition resultMap = repositoryXmlRegistry.findResultMap(resultMapId)
                .orElseThrow(() -> new IllegalStateException("resultMap 不存在：" + resultMapId));

        String discriminatorColumn = resultMap.discriminatorColumn();
        if (StringUtils.hasText(discriminatorColumn)) {
            Object discriminatorValue = row.get(discriminatorColumn);
            if (discriminatorValue != null) {
                String valueStr = String.valueOf(discriminatorValue);
                String mappedResultMapId = resultMap.discriminatorMappings().get(valueStr);
                if (StringUtils.hasText(mappedResultMapId)) {
                    return mapRowByResultMap(row, mappedResultMapId);
                }
            }
        }

        Class<?> targetClass = resolveClass(resultMap.type());
        try {
            Object entity = targetClass.getDeclaredConstructor().newInstance();
            for (Map.Entry<String, String> entry : resultMap.idMappings().entrySet()) {
                String column = entry.getKey();
                String property = entry.getValue();
                Object value = row.get(column);
                Field field = findField(targetClass, property);
                if (field != null) {
                    field.setAccessible(true);
                    field.set(entity, sqlExecutionEngine.convertValue(value, field.getType()));
                }
            }
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("通过 resultMap 映射实体失败：" + resultMapId, e);
        }
    }

    private Class<?> resolveClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("类不存在：" + className, e);
        }
    }

    private Field findField(Class<?> type, String name) {
        Class<?> searchType = type;
        while (searchType != null && searchType != Object.class) {
            try {
                return searchType.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                searchType = searchType.getSuperclass();
            }
        }
        return null;
    }

    private Number numberValue(Row row) {
        Object value = row.get(0);
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof CharSequence text) {
            return Long.parseLong(text.toString());
        }
        if (value == null) {
            return 0;
        }
        throw new IllegalArgumentException("R2DBC 查询结果不能转换为数字：" + value.getClass().getName());
    }

    private Class<?> reactiveValueType(Method method) {
        ResolvableType returnType = ResolvableType.forMethodReturnType(method);
        if (method.getReturnType() == Mono.class || method.getReturnType() == Flux.class) {
            Class<?> genericType = returnType.getGeneric(0).resolve();
            return genericType == null ? Object.class : genericType;
        }
        return method.getReturnType();
    }

    static Object terminatedPublisher(Method method) {
        return method.getReturnType() == Flux.class ? Flux.empty() : Mono.empty();
    }

    private Object typedNull(Object value, Class<?> javaType) {
        return value == null ? SqlParameter.nullOf(javaType == null ? Object.class : javaType) : value;
    }


}
