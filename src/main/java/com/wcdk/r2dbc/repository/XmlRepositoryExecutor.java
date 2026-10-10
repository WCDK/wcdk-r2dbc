package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.SqlLifecycleExecutor;
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
        XmlUpdateResultPlan result = XmlUpdateResultPlan.compile(method, repositoryInterface);
        if (result.entityArgumentIndex() >= 0) {
            Object entity = arguments[result.entityArgumentIndex()];
            if (entity == null) throw new IllegalArgumentException("XML 更新返回实体入参不能为 null：" + method);
            return rows.thenReturn(entity);
        }
        if (result.valueType() == Boolean.class) return rows.map(count -> count > 0);
        if (result.valueType() == Integer.class) return rows.map(Math::toIntExact);
        if (result.valueType() == Void.class) return rows.then();
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
            if (Map.class.isAssignableFrom(targetClass)) {
                return rowToMap(row);
            }
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
        if (Map.class.isAssignableFrom(valueType)) {
            return rowToMap(row);
        }
        if (Number.class.isAssignableFrom(valueType) || valueType == String.class || valueType == Boolean.class || valueType == boolean.class) {
            return sqlExecutionEngine.convertValue(row.get(0), valueType);
        }
        return sqlExecutionEngine.map(row, valueType);
    }

    private Map<String, Object> rowToMap(Row row) {
        Map<String, Object> map = new LinkedHashMap<>();
        row.getMetadata().getColumnMetadatas().forEach(column ->
                map.put(column.getName(), row.get(column.getName())));
        return map;
    }

    private Object mapRowByResultMap(Row row, String resultMapId) {
        ResultMapDefinition resultMap = repositoryXmlRegistry.findResultMap(resultMapId)
                .orElseThrow(() -> new IllegalStateException("resultMap 不存在：" + resultMapId));

        String discriminatorColumn = resultMap.discriminatorColumn();
        if (StringUtils.hasText(discriminatorColumn)) {
            Object value = resultMapColumn(row, discriminatorColumn, resultMap);
            String reference = resultMap.discriminatorMappings().get(String.valueOf(value));
            if (!StringUtils.hasText(reference)) {
                throw new IllegalStateException("resultMap " + resultMapId + " discriminator property/column "
                        + discriminatorColumn + " has no case for " + value + "; target " + resultMap.type());
            }
            return mapRowByResultMap(row, reference);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        resultMap.idMappings().forEach((column, property) ->
                values.put(property, resultMapColumn(row, column, resultMap)));
        resultMap.associationMappings().forEach((property, reference) ->
                values.put(property, mapRowByResultMap(row, reference)));
        return sqlExecutionEngine.mapProperties(values, resolveClass(resultMap.type()), resultMapId);
    }

    private Object resultMapColumn(Row row, String column, ResultMapDefinition resultMap) {
        try {
            String actual = row.getMetadata().getColumnMetadatas().stream()
                    .map(io.r2dbc.spi.ColumnMetadata::getName)
                    .filter(name -> name.equalsIgnoreCase(column)).findFirst().orElse(column);
            return row.get(actual);
        } catch (RuntimeException error) {
            throw new IllegalStateException("resultMap " + resultMap.id() + " column " + column
                    + " property " + resultMap.idMappings().get(column) + " target " + resultMap.type(), error);
        }
    }

    private Class<?> resolveClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("类不存在：" + className, e);
        }
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

}
