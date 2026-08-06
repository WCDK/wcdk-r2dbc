package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.core.interceptor.SqlExecutionContext;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorHolder;
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

    private final R2dbcUtil r2dbcUtil;

    private final WcdkR2dbcProperties properties;

    private final RepositoryMetadata metadata;

    private final Class<?> repositoryInterface;

    private final RepositoryXmlRegistry repositoryXmlRegistry;

    private final R2dbcDialect dialect;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    private final boolean snowflakeIdEnabled;

    RepositoryProxyMethodInterceptor(R2dbcUtil r2dbcUtil,
                                     WcdkR2dbcProperties properties,
                                     RepositoryMetadata metadata,
                                     Class<?> repositoryInterface,
                                     RepositoryXmlRegistry repositoryXmlRegistry,
                                     SnowflakeIdGenerator snowflakeIdGenerator) {
        this.r2dbcUtil = r2dbcUtil;
        this.properties = properties;
        this.metadata = metadata;
        this.repositoryInterface = repositoryInterface;
        this.repositoryXmlRegistry = repositoryXmlRegistry;
        this.dialect = DialectResolver.getDialect(r2dbcUtil.databaseClient().getConnectionFactory());
        this.snowflakeIdEnabled = properties.isSnowflakeId();
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Override
    public Object invoke(MethodInvocation invocation) {
        String methodName = invocation.getMethod().getName();
        Object[] arguments = invocation.getArguments();
        if (!isBaseMethod(methodName)) {
            // 1. 先尝试从XML注册表查找
            return repositoryXmlRegistry.find(repositoryInterface, methodName)
                    .map(statement -> executeXmlStatement(statement, invocation.getMethod(), arguments))
                    // 2. 尝试通过方法名约定解析自定义方法
                    .orElseGet(() -> executeCustomMethod(invocation.getMethod(), arguments));
        }
        if (metadata == null && isBaseCrudMethod(methodName)) {
            throw new UnsupportedOperationException("仓储接口未继承 BaseRepository，不支持基础 CRUD 方法：" + methodName);
        }
        return switch (methodName) {
            case "insert" -> insert(arguments[0]);
            case "deleteById" -> deleteById(arguments[0]);
            case "updateById" -> updateById(arguments[0]);
            case "selectById" -> selectById(arguments[0]);
            case "selectList" -> selectList(queryWrapper(arguments));
            case "selectPage" -> selectPage(pageable(arguments), queryWrapper(arguments, 1));
            case "selectOne" -> selectOne(queryWrapper(arguments));
            case "selectCount" -> selectCount(queryWrapper(arguments));
            case "exists" -> exists(queryWrapper(arguments));
            case "toString" -> "WcdkR2dbcRepositoryProxy(" + (metadata != null ? metadata.entityClass().getName() : repositoryInterface.getSimpleName()) + ")";
            case "hashCode" -> System.identityHashCode(invocation.getThis());
            case "equals" -> invocation.getThis() == arguments[0];
            default -> throw new UnsupportedOperationException("暂不支持自定义仓储方法：" + methodName);
        };
    }

    private boolean isBaseMethod(String methodName) {
        return isBaseCrudMethod(methodName) || switch (methodName) {
            case "toString", "hashCode", "equals" -> true;
            default -> false;
        };
    }

    private static boolean isBaseCrudMethod(String methodName) {
        return switch (methodName) {
            case "insert", "deleteById", "updateById", "selectById", "selectList", "selectPage", "selectOne", "selectCount",
                 "exists" -> true;
            default -> false;
        };
    }

    /**
     * 执行自定义方法（通过方法名约定解析）。
     */
    private Object executeCustomMethod(Method method, Object[] arguments) {
        if (metadata == null) {
            throw new UnsupportedOperationException("仓储接口未继承 BaseRepository，不支持自定义方法：" + method.getName());
        }

        CustomMethodResolver resolver = new CustomMethodResolver(metadata);
        CustomMethodResolver.ParsedMethod parsedMethod = resolver.resolve(method, arguments);

        if (parsedMethod == null) {
            throw new UnsupportedOperationException("暂不支持自定义仓储方法：" + method.getName());
        }

        SqlLifecycleInterceptorChain chain = SqlLifecycleInterceptorHolder.getChain();
        SqlExecutionContext context = new SqlExecutionContext(method, repositoryInterface, arguments);
        context.setSql(parsedMethod.sql());
        context.setParameters(parsedMethod.parameters());

        // beforeCompile
        if (chain.beforeCompile(context)) {
            return terminatedPublisher(method);
        }

        // afterCompile
        if (chain.afterCompile(context)) {
            return terminatedPublisher(method);
        }

        // beforeExecute
        if (chain.beforeExecute(context)) {
            return terminatedPublisher(method);
        }

        context.setStartTime(System.nanoTime());

        if (parsedMethod.isQuery()) {
            // 查询操作
            Flux<?> flux = r2dbcUtil.queryWithoutLifecycle(context.getSql(), context.getParameters(),
                    (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass()));

            return flux.doOnComplete(() -> {
                context.setEndTime(System.nanoTime());
                chain.afterExecute(context);
            }).doOnError(e -> {
                context.setError(e);
                context.setEndTime(System.nanoTime());
                chain.afterExecute(context);
            });
        } else {
            // 更新操作
            return r2dbcUtil.updateWithoutLifecycle(context.getSql(), context.getParameters())
                    .doOnSuccess(r -> {
                        context.setResult(r);
                        context.setEndTime(System.nanoTime());
                        chain.afterExecute(context);
                    })
                    .doOnError(e -> {
                        context.setError(e);
                        context.setEndTime(System.nanoTime());
                        chain.afterExecute(context);
                    });
        }
    }

    private Object executeXmlStatement(RepositoryStatement statement, Method method, Object[] arguments) {
        SqlLifecycleInterceptorChain chain = SqlLifecycleInterceptorHolder.getChain();
        SqlExecutionContext context = new SqlExecutionContext(method, repositoryInterface, arguments);
        context.setParameters(methodParameters(method, arguments));

        // beforeCompile
        if (chain.beforeCompile(context)) {
            return terminatedPublisher(method);
        }

        DynamicSqlSource.RenderedSql renderedSql = statement.render(context.getParameters());
        Map<String, Object> sourceParameters = new LinkedHashMap<>(context.getParameters());
        sourceParameters.putAll(renderedSql.additionalParameters());
        BoundSql boundSql = bindSql(renderedSql.sql(), arguments, sourceParameters);
        context.setSql(boundSql.sql());
        context.setParameters(boundSql.parameters());

        // afterCompile
        if (chain.afterCompile(context)) {
            return terminatedPublisher(method);
        }

        // 使用可能被修改的SQL
        BoundSql finalBoundSql = new BoundSql(context.getSql(), context.getParameters());

        // beforeExecute
        if (chain.beforeExecute(context)) {
            return terminatedPublisher(method);
        }

        context.setStartTime(System.nanoTime());

        Object result;
        try {
            result = switch (statement.commandType()) {
                case INSERT, UPDATE, DELETE -> executeXmlUpdate(finalBoundSql, method, arguments);
                case SELECT -> executeXmlSelect(finalBoundSql, method, statement);
            };
        } catch (Exception e) {
            context.setError(e);
            context.setEndTime(System.nanoTime());
            chain.afterExecute(context);
            throw e;
        }

        // 包装结果以捕获异步完成
        if (result instanceof Mono<?> mono) {
            return mono.doOnSuccess(r -> {
                context.setResult(r);
                context.setEndTime(System.nanoTime());
                chain.afterExecute(context);
            }).doOnError(e -> {
                context.setError(e);
                context.setEndTime(System.nanoTime());
                chain.afterExecute(context);
            });
        } else if (result instanceof Flux<?> flux) {
            return flux.doOnComplete(() -> {
                context.setEndTime(System.nanoTime());
                chain.afterExecute(context);
            }).doOnError(e -> {
                context.setError(e);
                context.setEndTime(System.nanoTime());
                chain.afterExecute(context);
            });
        }

        context.setResult(result);
        context.setEndTime(System.nanoTime());
        chain.afterExecute(context);
        return result;
    }

    private Object executeXmlUpdate(BoundSql boundSql, Method method, Object[] arguments) {
        Mono<Long> rows = r2dbcUtil.updateWithoutLifecycle(boundSql.sql(), boundSql.parameters());
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

    private Object executeXmlSelect(BoundSql boundSql, Method method, RepositoryStatement statement) {
        Class<?> valueType = reactiveValueType(method);
        String resultType = statement.resultType();
        String resultMapId = statement.resultMapId();

        if (method.getReturnType() == Flux.class) {
            return r2dbcUtil.queryWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) ->
                    mapXmlRow(row, valueType, resultType, resultMapId));
        }
        if (valueType == Boolean.class || valueType == boolean.class) {
            return r2dbcUtil.queryOneWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) -> numberValue(row).longValue() > 0)
                    .defaultIfEmpty(false);
        }
        if (valueType == Long.class || valueType == long.class) {
            return r2dbcUtil.queryOneWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) -> numberValue(row).longValue())
                    .defaultIfEmpty(0L);
        }
        if (valueType == Integer.class || valueType == int.class) {
            return r2dbcUtil.queryOneWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) -> numberValue(row).intValue())
                    .defaultIfEmpty(0);
        }
        return r2dbcUtil.queryOneWithoutLifecycle(boundSql.sql(), boundSql.parameters(), (row, rowMetadata) ->
                mapXmlRow(row, valueType, resultType, resultMapId));
    }

    private Object mapXmlRow(Row row, Class<?> valueType, String resultType, String resultMapId) {
        if (StringUtils.hasText(resultMapId)) {
            return mapRowByResultMap(row, resultMapId);
        }
        if (StringUtils.hasText(resultType)) {
            Class<?> targetClass = resolveClass(resultType);
            if (Number.class.isAssignableFrom(targetClass) || targetClass == String.class || targetClass == Boolean.class || targetClass == boolean.class) {
                return r2dbcUtil.convertValue(row.get(0), targetClass);
            }
            return r2dbcUtil.map(row, targetClass);
        }
        if (valueType == Object.class) {
            if (metadata == null) {
                throw new IllegalStateException("无法确定实体类型，请在 XML 中明确指定返回值类型或继承 BaseRepository");
            }
            return r2dbcUtil.map(row, metadata.entityClass());
        }
        if (Number.class.isAssignableFrom(valueType) || valueType == String.class || valueType == Boolean.class || valueType == boolean.class) {
            return r2dbcUtil.convertValue(row.get(0), valueType);
        }
        return r2dbcUtil.map(row, valueType);
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
                    field.set(entity, r2dbcUtil.convertValue(value, field.getType()));
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

    private BoundSql bindSql(String sql, Object[] arguments, Map<String, Object> sourceParameters) {
        Map<String, Object> boundParameters = new LinkedHashMap<>();
        List<String> parameterNames = new ArrayList<>();
        Matcher matcher = PARAMETER_PATTERN.matcher(sql);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            parameterNames.add(name);
            matcher.appendReplacement(builder, ":" + bindName(name));
        }
        matcher.appendTail(builder);
        if ((arguments == null ? 0 : arguments.length) == 1 && parameterNames.size() == 1
                && !sourceParameters.containsKey(parameterNames.get(0))) {
            boundParameters.put(bindName(parameterNames.get(0)), arguments[0]);
            return new BoundSql(builder.toString(), boundParameters);
        }
        for (String name : parameterNames) {
            boundParameters.put(bindName(name), parameterValue(sourceParameters, name));
        }
        return new BoundSql(builder.toString(), boundParameters);
    }

    static Object terminatedPublisher(Method method) {
        return method.getReturnType() == Flux.class ? Flux.empty() : Mono.empty();
    }

    private Map<String, Object> methodParameters(Method method, Object[] arguments) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (arguments == null) {
            return parameters;
        }
        String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            parameters.put("arg" + i, argument);
            parameters.put("param" + (i + 1), argument);
            MethodParameter methodParameter = new MethodParameter(method, i);
            Param param = methodParameter.getParameterAnnotation(Param.class);
            if (param != null) {
                parameters.put(param.value(), argument);
            }
            if (parameterNames != null && StringUtils.hasText(parameterNames[i])) {
                parameters.put(parameterNames[i], argument);
            }
            if (arguments.length == 1 && !isSimpleValue(argument)) {
                putBeanProperties(parameters, argument);
            }
        }
        return parameters;
    }

    private Object parameterValue(Map<String, Object> parameters, String name) {
        if (parameters.containsKey(name)) {
            return parameters.get(name);
        }
        int dotIndex = name.indexOf('.');
        if (dotIndex > 0) {
            Object root = parameters.get(name.substring(0, dotIndex));
            if (root != null) {
                return propertyValue(root, name.substring(dotIndex + 1));
            }
        }
        throw new IllegalArgumentException("R2DBC XML SQL 参数不存在：" + name);
    }

    private String bindName(String name) {
        return name.replace('.', '_');
    }

    private void putBeanProperties(Map<String, Object> parameters, Object argument) {
        for (Field field : argument.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            parameters.putIfAbsent(field.getName(), fieldValue(field, argument));
        }
    }

    private Object propertyValue(Object root, String propertyPath) {
        Object value = root;
        for (String name : propertyPath.split("\\.")) {
            if (value == null) {
                return null;
            }
            value = fieldValue(field(value.getClass(), name), value);
        }
        return value;
    }

    private Field field(Class<?> type, String name) {
        Class<?> searchType = type;
        while (searchType != null && searchType != Object.class) {
            try {
                Field field = searchType.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                searchType = searchType.getSuperclass();
            }
        }
        throw new IllegalArgumentException("R2DBC XML SQL 参数字段不存在：" + type.getName() + "." + name);
    }

    private boolean isSimpleValue(Object value) {
        return value == null
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>;
    }

    private Mono<?> insert(Object entity) {
        SqlLifecycleInterceptorChain chain = SqlLifecycleInterceptorHolder.getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("insert"), repositoryInterface, new Object[]{entity});

        // beforeCompile
        if (chain.beforeCompile(context)) {
            return Mono.empty();
        }

        if (snowflakeIdEnabled) {
            Object idValue = fieldValue(metadata.idColumn(), entity);
            if (idValue == null || (idValue instanceof Number number && number.longValue() == 0)) {
                long snowflakeId = snowflakeIdGenerator.nextId();
                try {
                    Field idField = metadata.idColumn().field();
                    idField.setAccessible(true);
                    if (idField.getType() == Long.class || idField.getType() == long.class) {
                        idField.set(entity, snowflakeId);
                    } else if (idField.getType() == String.class) {
                        idField.set(entity, String.valueOf(snowflakeId));
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("无法设置雪花ID到实体字段：" + metadata.idColumn().field().getName(), e);
                }
            }
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        String fields = metadata.columns().stream().map(FieldColumn::name).collect(Collectors.joining(", "));
        String values = metadata.columns().stream()
                .peek(column -> parameters.put(column.field().getName(), fieldValue(column, entity)))
                .map(column -> ":" + column.field().getName())
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + metadata.tableName() + " (" + fields + ") VALUES (" + values + ")";

        context.setSql(sql);
        context.setParameters(parameters);

        // afterCompile
        if (chain.afterCompile(context)) {
            return Mono.empty();
        }

        // beforeExecute
        if (chain.beforeExecute(context)) {
            return Mono.empty();
        }

        context.setStartTime(System.nanoTime());

        return r2dbcUtil.updateWithoutLifecycle(context.getSql(), context.getParameters())
                .doOnSuccess(r -> {
                    context.setResult(entity);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                })
                .doOnError(e -> {
                    context.setError(e);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                })
                .thenReturn(entity);
    }

    private Mono<Long> deleteById(Object id) {
        SqlLifecycleInterceptorChain chain = SqlLifecycleInterceptorHolder.getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("deleteById"), repositoryInterface, new Object[]{id});

        // beforeCompile
        if (chain.beforeCompile(context)) {
            return Mono.empty();
        }

        FieldColumn logicDeleteColumn = metadata.logicDeleteColumn();
        String sql;
        Map<String, Object> parameters;
        if (logicDeleteColumn != null) {
            sql = "UPDATE " + metadata.tableName()
                    + " SET " + logicDeleteColumn.name() + " = :logicDeleteValue"
                    + " WHERE " + metadata.idColumn().name() + " = :id"
                    + logicNotDeleteSql(" AND ");
            parameters = Map.of(
                    "id", id,
                    "logicDeleteValue", properties.getLogicDeleteValue(),
                    "logicNotDeleteValue", properties.getLogicNotDeleteValue());
        } else {
            sql = "DELETE FROM " + metadata.tableName() + " WHERE " + metadata.idColumn().name() + " = :id";
            parameters = Map.of("id", id);
        }

        context.setSql(sql);
        context.setParameters(parameters);

        // afterCompile
        if (chain.afterCompile(context)) {
            return Mono.empty();
        }

        // beforeExecute
        if (chain.beforeExecute(context)) {
            return Mono.empty();
        }

        context.setStartTime(System.nanoTime());

        return r2dbcUtil.updateWithoutLifecycle(context.getSql(), context.getParameters())
                .doOnSuccess(r -> {
                    context.setResult(r);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                })
                .doOnError(e -> {
                    context.setError(e);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                });
    }

    private Mono<Long> updateById(Object entity) {
        SqlLifecycleInterceptorChain chain = SqlLifecycleInterceptorHolder.getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("updateById"), repositoryInterface, new Object[]{entity});

        // beforeCompile
        if (chain.beforeCompile(context)) {
            return Mono.empty();
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        String setSql = metadata.columns().stream()
                .filter(column -> column != metadata.idColumn())
                .filter(column -> fieldValue(column, entity) != null)
                .peek(column -> parameters.put(column.field().getName(), fieldValue(column, entity)))
                .map(column -> column.name() + " = :" + column.field().getName())
                .collect(Collectors.joining(", "));
        if (setSql.isBlank()) {
            return Mono.just(0L);
        }
        Object id = fieldValue(metadata.idColumn(), entity);
        parameters.put("id", id);
        if (metadata.logicDeleteColumn() != null) {
            parameters.put("logicNotDeleteValue", properties.getLogicNotDeleteValue());
        }
        String sql = "UPDATE " + metadata.tableName()
                + " SET " + setSql
                + " WHERE " + metadata.idColumn().name() + " = :id"
                + logicNotDeleteSql(" AND ");

        context.setSql(sql);
        context.setParameters(parameters);

        // afterCompile
        if (chain.afterCompile(context)) {
            return Mono.empty();
        }

        // beforeExecute
        if (chain.beforeExecute(context)) {
            return Mono.empty();
        }

        context.setStartTime(System.nanoTime());

        return r2dbcUtil.updateWithoutLifecycle(context.getSql(), context.getParameters())
                .doOnSuccess(r -> {
                    context.setResult(r);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                })
                .doOnError(e -> {
                    context.setError(e);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                });
    }

    private Mono<?> selectById(Object id) {
        SqlLifecycleInterceptorChain chain = SqlLifecycleInterceptorHolder.getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectById"), repositoryInterface, new Object[]{id});

        // beforeCompile
        if (chain.beforeCompile(context)) {
            return Mono.empty();
        }

        String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName()
                + " WHERE " + metadata.idColumn().name() + " = :id"
                + logicNotDeleteSql(" AND ");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("id", id);
        if (metadata.logicDeleteColumn() != null) {
            parameters.put("logicNotDeleteValue", properties.getLogicNotDeleteValue());
        }

        context.setSql(sql);
        context.setParameters(parameters);

        // afterCompile
        if (chain.afterCompile(context)) {
            return Mono.empty();
        }

        // beforeExecute
        if (chain.beforeExecute(context)) {
            return Mono.empty();
        }

        context.setStartTime(System.nanoTime());

        return r2dbcUtil.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass()))
                .doOnSuccess(r -> {
                    context.setResult(r);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                })
                .doOnError(e -> {
                    context.setError(e);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                });
    }

    private Flux<?> selectList(QueryWrapper<?> queryWrapper) {
        SqlLifecycleInterceptorChain chain = SqlLifecycleInterceptorHolder.getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectList"), repositoryInterface, new Object[]{queryWrapper});

        // beforeCompile
        if (chain.beforeCompile(context)) {
            return Flux.empty();
        }

        SqlWhere where = buildWhere(queryWrapper);
        String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName() + where.sql()
                + orderBySql(queryWrapper)
                + limitSql(queryWrapper);

        context.setSql(sql);
        context.setParameters(where.parameters());

        // afterCompile
        if (chain.afterCompile(context)) {
            return Flux.empty();
        }

        // beforeExecute
        if (chain.beforeExecute(context)) {
            return Flux.empty();
        }

        context.setStartTime(System.nanoTime());

        return r2dbcUtil.queryWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass()))
                .doOnComplete(() -> {
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                })
                .doOnError(e -> {
                    context.setError(e);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                });
    }

    private Mono<?> selectOne(QueryWrapper<?> queryWrapper) {
        QueryWrapper<?> wrapper = queryWrapper == null ? new QueryWrapper<>() : queryWrapper;
        if (wrapper.limit() == null) {
            wrapper.limit(1);
        }
        return selectList(wrapper).next();
    }

    private Mono<?> selectPage(Pageable pageable, QueryWrapper<?> queryWrapper) {
        if (pageable == null) {
            throw new IllegalArgumentException("分页参数不能为空");
        }
        SqlWhere where = buildWhere(queryWrapper);
        String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName() + where.sql()
                + orderBySql(queryWrapper)
                + pageSql(pageable);
        Mono<List<Object>> records = r2dbcUtil.query(sql, where.parameters(), (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass()))
                .cast(Object.class)
                .collectList();
        return Mono.zip(records, selectCount(queryWrapper))
                .map(tuple -> new PageImpl<>(tuple.getT1(), pageable, tuple.getT2()));
    }

    private Mono<Long> selectCount(QueryWrapper<?> queryWrapper) {
        SqlLifecycleInterceptorChain chain = SqlLifecycleInterceptorHolder.getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectCount"), repositoryInterface, new Object[]{queryWrapper});

        // beforeCompile
        if (chain.beforeCompile(context)) {
            return Mono.empty();
        }

        SqlWhere where = buildWhere(queryWrapper);
        String sql = "SELECT COUNT(1) AS total FROM " + metadata.tableName() + where.sql();

        context.setSql(sql);
        context.setParameters(where.parameters());

        // afterCompile
        if (chain.afterCompile(context)) {
            return Mono.empty();
        }

        // beforeExecute
        if (chain.beforeExecute(context)) {
            return Mono.empty();
        }

        context.setStartTime(System.nanoTime());

        return r2dbcUtil.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> numberValue(row).longValue())
                .defaultIfEmpty(0L)
                .doOnSuccess(r -> {
                    context.setResult(r);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                })
                .doOnError(e -> {
                    context.setError(e);
                    context.setEndTime(System.nanoTime());
                    chain.afterExecute(context);
                });
    }

    private Mono<Boolean> exists(QueryWrapper<?> queryWrapper) {
        return selectCount(queryWrapper).map(count -> count > 0);
    }

    private SqlWhere buildWhere(QueryWrapper<?> queryWrapper) {
        QueryWrapper<?> wrapper = queryWrapper == null ? new QueryWrapper<>() : queryWrapper;
        Map<String, Object> parameters = new LinkedHashMap<>();
        StringBuilder builder = new StringBuilder();
        if (metadata.logicDeleteColumn() != null) {
            builder.append(" WHERE ").append(metadata.logicDeleteColumn().name()).append(" = :logicNotDeleteValue");
            parameters.put("logicNotDeleteValue", properties.getLogicNotDeleteValue());
        }
        int index = 0;
        for (QueryWrapper.Condition condition : wrapper.conditions()) {
            FieldColumn column = metadata.columnByName(condition.column());
            String parameterName = "p" + index++;
            builder.append(builder.isEmpty() ? " WHERE " : " AND ")
                    .append(column.name()).append(" ").append(condition.operator()).append(" :").append(parameterName);
            parameters.put(parameterName, condition.value());
        }
        return new SqlWhere(builder.toString(), parameters);
    }

    private String orderBySql(QueryWrapper<?> queryWrapper) {
        if (queryWrapper == null || queryWrapper.orderByList().isEmpty()) {
            return "";
        }
        return queryWrapper.orderByList().stream()
                .map(orderBy -> metadata.columnByName(orderBy.column()).name() + (orderBy.asc() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", ", " ORDER BY ", ""));
    }

    private String limitSql(QueryWrapper<?> queryWrapper) {
        if (queryWrapper == null || queryWrapper.limit() == null) {
            return "";
        }
        Long offset = queryWrapper.offset() == null ? null : queryWrapper.offset().longValue();
        return paginationSql(queryWrapper.limit(), offset);
    }

    private String pageSql(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return "";
        }
        return paginationSql(pageable.getPageSize(), pageable.getOffset());
    }

    private String paginationSql(long limit, Long offset) {
        return DialectPagination.render(dialect, limit, offset);
    }

    private String logicNotDeleteSql(String prefix) {
        FieldColumn logicDeleteColumn = metadata.logicDeleteColumn();
        return logicDeleteColumn == null ? "" : prefix + logicDeleteColumn.name() + " = :logicNotDeleteValue";
    }

    private QueryWrapper<?> queryWrapper(Object[] arguments) {
        return arguments == null || arguments.length == 0 ? new QueryWrapper<>() : (QueryWrapper<?>) arguments[0];
    }

    private QueryWrapper<?> queryWrapper(Object[] arguments, int index) {
        return arguments == null || arguments.length <= index ? new QueryWrapper<>() : (QueryWrapper<?>) arguments[index];
    }

    private Pageable pageable(Object[] arguments) {
        return arguments == null || arguments.length == 0 ? Pageable.unpaged() : (Pageable) arguments[0];
    }

    private String selectColumns() {
        return metadata.columns().stream().map(FieldColumn::name).collect(Collectors.joining(", "));
    }

    private Method findMethod(String methodName) {
        try {
            for (Method method : repositoryInterface.getMethods()) {
                if (method.getName().equals(methodName)) {
                    return method;
                }
            }
            // fallback to Object methods
            return Object.class.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Method not found: " + methodName, e);
        }
    }

    private Object fieldValue(FieldColumn column, Object entity) {
        return fieldValue(column.field(), entity);
    }

    private Object fieldValue(Field field, Object entity) {
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取实体字段失败：" + field.getName(), e);
        }
    }

    private record SqlWhere(String sql, Map<String, Object> parameters) {
    }

    private record BoundSql(String sql, Map<String, Object> parameters) {
    }
}
