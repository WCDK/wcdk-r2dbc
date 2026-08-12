package com.wcdk.r2dbc.core;

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

    private final R2dbcUtil r2dbcUtil;

    private final WcdkR2dbcProperties properties;

    private final RepositoryMetadata metadata;

    private final Class<?> repositoryInterface;

    private final RepositoryXmlRegistry repositoryXmlRegistry;

    private final R2dbcDialect dialect;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    private final boolean snowflakeIdEnabled;

    private final CustomMethodResolver customMethodResolver;

    private final Map<Method, RepositoryMethodPlan> methodPlans;

    private final RepositoryInvocationDispatcher dispatcher;

    RepositoryProxyMethodInterceptor(R2dbcUtil r2dbcUtil,
                                     WcdkR2dbcProperties properties,
                                     RepositoryMetadata metadata,
                                     Class<?> repositoryInterface,
                                     RepositoryXmlRegistry repositoryXmlRegistry,
                                     SnowflakeIdGenerator snowflakeIdGenerator,
                                     Map<Method, RepositoryMethodPlan> methodPlans) {
        this.r2dbcUtil = r2dbcUtil;
        this.properties = properties;
        this.metadata = metadata;
        this.repositoryInterface = repositoryInterface;
        this.repositoryXmlRegistry = repositoryXmlRegistry;
        this.dialect = DialectResolver.getDialect(r2dbcUtil.databaseClient().getConnectionFactory());
        this.snowflakeIdEnabled = properties.isSnowflakeId();
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.customMethodResolver = metadata == null ? null : new CustomMethodResolver(metadata,
                properties.getLogicDeleteValue(), properties.getLogicNotDeleteValue());
        this.methodPlans = Map.copyOf(methodPlans);
        this.dispatcher = new RepositoryInvocationDispatcher(List.of(
                new CrudMethodExecutor(this::executePlan),
                new DerivedMethodExecutor(this::executePlan),
                new XmlMethodExecutor(this::executePlan)));
    }

    @Override
    public Object invoke(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        RepositoryMethodPlan plan = methodPlans.get(method);
        if (plan == null) {
            throw new IllegalStateException("Repository方法未在启动时编译: " + method);
        }
        if (plan.kind() == RepositoryMethodPlan.Kind.OBJECT) {
            return executeObjectMethod(new RepositoryInvocation(plan, invocation.getThis(), invocation.getArguments()));
        }
        if (method.getReturnType() == Mono.class) {
            return Mono.deferContextual(context -> {
                Object result = invokeOnce(new RepositoryInvocation(plan, invocation.getThis(), invocation.getArguments()), context);
                return result instanceof Mono<?> mono ? mono : Mono.justOrEmpty(result);
            });
        }
        if (method.getReturnType() == Flux.class) {
            return Flux.deferContextual(context -> {
                Object result = invokeOnce(new RepositoryInvocation(plan, invocation.getThis(), invocation.getArguments()), context);
                if (result instanceof org.reactivestreams.Publisher<?> publisher) {
                    @SuppressWarnings("unchecked")
                    org.reactivestreams.Publisher<Object> typed =
                            (org.reactivestreams.Publisher<Object>) publisher;
                    return Flux.from(typed);
                }
                return Flux.just(result);
            });
        }
        return invokeOnce(new RepositoryInvocation(plan, invocation.getThis(), invocation.getArguments()), reactor.util.context.Context.empty());
    }

    /** Builds all mutable execution state for one subscription only. */
    private Object invokeOnce(RepositoryInvocation invocation, ContextView context) {
        return dispatcher.execute(invocation.plan(), invocation.arguments(), context);
    }

    /** Executes the selected plan; routing is handled by RepositoryInvocationDispatcher. */
    private Object executePlan(RepositoryMethodPlan plan, Object[] arguments, ContextView context) {
        Method method = plan.method();
        String methodName = method.getName();
        if (plan.kind() == RepositoryMethodPlan.Kind.XML) {
            return executeXmlStatement(plan.xmlStatement(), method, arguments);
        }
        if (plan.kind() == RepositoryMethodPlan.Kind.DERIVED) {
            return executeCustomMethod(method, arguments);
        }
        if (plan.kind() == RepositoryMethodPlan.Kind.UNSUPPORTED) {
            throw new UnsupportedOperationException("不支持的Repository方法: " + method.toGenericString());
        }
        if (plan.kind() != RepositoryMethodPlan.Kind.CRUD
                && plan.kind() != RepositoryMethodPlan.Kind.OBJECT) {
            // 1. 先尝试从XML注册表查找
            return repositoryXmlRegistry.find(repositoryInterface, methodName)
                    .map(statement -> executeXmlStatement(statement, method, arguments))
                    // 2. 尝试通过方法名约定解析自定义方法
                    .orElseGet(() -> executeCustomMethod(method, arguments));
        }
        if (metadata == null && isBaseCrudMethod(methodName)) {
            throw new UnsupportedOperationException("仓储接口未继承 BaseRepository，不支持基础 CRUD 方法：" + methodName);
        }
        return switch (methodName) {
            case "insert" -> insert(arguments[0]);
            case "deleteById" -> deleteById(arguments[0]);
            case "updateById" -> updateById(arguments[0]);
            case "selectById" -> selectById(arguments[0]);
            case "findAll" -> selectList(new QueryWrapper<>(), context);
            case "selectList" -> selectList(queryWrapper(arguments), context);
            case "selectPage" -> selectPage(pageable(arguments), queryWrapper(arguments, 1), context);
            case "selectOne" -> selectOne(queryWrapper(arguments), context);
            case "selectCount" -> selectCount(queryWrapper(arguments), context);
            case "exists" -> exists(queryWrapper(arguments), context);
            case "toString" -> "WcdkR2dbcRepositoryProxy(" + (metadata != null ? metadata.entityClass().getName() : repositoryInterface.getSimpleName()) + ")";
            case "hashCode" -> System.identityHashCode(arguments.length == 0 ? null : arguments[0]);
            case "equals" -> false;
            default -> throw new UnsupportedOperationException("暂不支持自定义仓储方法：" + methodName);
        };
    }

    private Object executeObjectMethod(RepositoryInvocation invocation) {
        return switch (invocation.getMethod().getName()) {
            case "toString" -> "WcdkR2dbcRepositoryProxy(" + (metadata != null ? metadata.entityClass().getName() : repositoryInterface.getSimpleName()) + ")";
            case "hashCode" -> System.identityHashCode(invocation.getThis());
            case "equals" -> invocation.getThis() == invocation.arguments()[0];
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + invocation.getMethod());
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
            case "insert", "deleteById", "updateById", "selectById", "findAll", "selectList", "selectPage", "selectOne", "selectCount",
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

        CustomMethodResolver.ParsedMethod parsedMethod = customMethodResolver.resolve(method, arguments);

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
                () -> r2dbcUtil.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                                (row, rowMetadata) -> numberValue(row).longValue())
                        .defaultIfEmpty(0L));
    }

    private Object executeCustomExistsQuery(Mono<Boolean> lifecycle, SqlExecutionContext context,
                                             SqlLifecycleInterceptorChain chain) {
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> r2dbcUtil.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                                (row, rowMetadata) -> numberValue(row).longValue() > 0)
                        .defaultIfEmpty(false));
    }

    private Object executeCustomFindQueryFlux(Mono<Boolean> lifecycle, SqlExecutionContext context,
                                               SqlLifecycleInterceptorChain chain) {
        return lifecycleExecutor().executeFlux(chain, context, lifecycle,
                () -> r2dbcUtil.queryWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass())));
    }

    private Object executeCustomFindQueryMono(Mono<Boolean> lifecycle, SqlExecutionContext context,
                                               SqlLifecycleInterceptorChain chain) {
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> r2dbcUtil.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass())));
    }

    private Object executeCustomUpdate(Mono<Boolean> lifecycle, Method method, Class<?> valueType,
                                        SqlExecutionContext context, SqlLifecycleInterceptorChain chain) {
        Mono<Long> updateMono = lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> r2dbcUtil.updateWithoutLifecycle(context.getSql(), context.getParameters()));

        if (method.getReturnType() == Mono.class && valueType == Boolean.class) {
            return updateMono.map(count -> count > 0);
        }
        if (method.getReturnType() == Mono.class && valueType == Void.class) {
            return updateMono.then();
        }
        return updateMono;
    }

    private Object executeXmlStatement(RepositoryStatement statement, Method method, Object[] arguments) {
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(method, repositoryInterface, arguments);
        context.setParameters(methodParameters(method, arguments));
        context.setCommandType(statement.commandType());

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    DynamicSqlSource.RenderedSql renderedSql = statement.render(context.getParameters());
                    Map<String, Object> sourceParameters = new LinkedHashMap<>(context.getParameters());
                    sourceParameters.putAll(renderedSql.additionalParameters());
                    BoundSql boundSql = bindSql(renderedSql.sql(), method, arguments, sourceParameters);
                    context.setSql(boundSql.sql());
                    context.setParameters(boundSql.parameters());
                }));

        boolean returnsFlux = method.getReturnType() == Flux.class;

        if (returnsFlux) {
            return lifecycleExecutor().executeFlux(chain, context, lifecycle,
                    () -> Flux.defer(() -> {
                        BoundSql finalBoundSql = new BoundSql(context.getSql(), context.getParameters());

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
                        BoundSql finalBoundSql = new BoundSql(context.getSql(), context.getParameters());

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

    private BoundSql bindSql(String sql, Method method, Object[] arguments, Map<String, Object> sourceParameters) {
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
            boundParameters.put(bindName(parameterNames.get(0)), typedNull(arguments[0], method.getParameterTypes()[0]));
            return new BoundSql(builder.toString(), boundParameters);
        }
        for (String name : parameterNames) {
            Object value = parameterValue(sourceParameters, name);
            boundParameters.put(bindName(name), typedNull(value, parameterJavaType(method, name)));
        }
        return new BoundSql(builder.toString(), boundParameters);
    }

    private Object typedNull(Object value, Class<?> javaType) {
        return value == null ? SqlParameter.nullOf(javaType == null ? Object.class : javaType) : value;
    }

    private Class<?> parameterJavaType(Method method, String name) {
        String rootName = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String[] discoveredNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        for (int i = 0; i < method.getParameterCount(); i++) {
            MethodParameter parameter = new MethodParameter(method, i);
            Param annotation = parameter.getParameterAnnotation(Param.class);
            boolean matches = rootName.equals("arg" + i) || rootName.equals("param" + (i + 1))
                    || annotation != null && rootName.equals(annotation.value())
                    || discoveredNames != null && rootName.equals(discoveredNames[i]);
            if (!matches) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[i];
            if (name.contains(".")) {
                return field(type, name.substring(name.indexOf('.') + 1)).getType();
            }
            return type;
        }
        if (method.getParameterCount() == 1 && name.contains(".")) {
            return field(method.getParameterTypes()[0], name.substring(name.indexOf('.') + 1)).getType();
        }
        if (method.getParameterCount() == 1) {
            Field beanField = findField(method.getParameterTypes()[0], name);
            if (beanField != null) {
                return beanField.getType();
            }
        }
        return Object.class;
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
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("insert"), repositoryInterface, new Object[]{entity});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    if (snowflakeIdEnabled && metadata.hasIdColumn()) {
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
                            .peek(column -> parameters.put(column.field().getName(),
                                    typedNull(fieldValue(column, entity), column.field().getType())))
                            .map(column -> ":" + column.field().getName())
                            .collect(Collectors.joining(", "));
                    String sql = "INSERT INTO " + metadata.tableName() + " (" + fields + ") VALUES (" + values + ")";

                    context.setSql(sql);
                    context.setParameters(parameters);
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> r2dbcUtil.updateWithoutLifecycle(context.getSql(), context.getParameters())
                        .thenReturn(entity));
    }

    private Mono<Long> deleteById(Object id) {
        FieldColumn idColumn = metadata.requireIdColumn();
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("deleteById"), repositoryInterface, new Object[]{id});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    FieldColumn logicDeleteColumn = metadata.logicDeleteColumn();
                    String sql;
                    Map<String, Object> parameters;
                    if (logicDeleteColumn != null) {
                        sql = "UPDATE " + metadata.tableName()
                                + " SET " + logicDeleteColumn.name() + " = :logicDeleteValue"
                                + " WHERE " + idColumn.name() + " = :id"
                                + logicNotDeleteSql(" AND ");
                        parameters = Map.of(
                                "id", id,
                                "logicDeleteValue", properties.getLogicDeleteValue(),
                                "logicNotDeleteValue", properties.getLogicNotDeleteValue());
                    } else {
                        sql = "DELETE FROM " + metadata.tableName() + " WHERE " + idColumn.name() + " = :id";
                        parameters = Map.of("id", id);
                    }

                    context.setSql(sql);
                    context.setParameters(parameters);
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> r2dbcUtil.updateWithoutLifecycle(context.getSql(), context.getParameters()));
    }

    private Mono<Long> updateById(Object entity) {
        FieldColumn idColumn = metadata.requireIdColumn();
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("updateById"), repositoryInterface, new Object[]{entity});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    Map<String, Object> parameters = new LinkedHashMap<>();
                    String setSql = metadata.columns().stream()
                            .filter(column -> column != idColumn)
                            .filter(column -> fieldValue(column, entity) != null)
                            .peek(column -> parameters.put(column.field().getName(), fieldValue(column, entity)))
                            .map(column -> column.name() + " = :" + column.field().getName())
                            .collect(Collectors.joining(", "));
                    if (setSql.isBlank()) {
                        context.cacheHit(0L);
                        return;
                    }
                    Object id = fieldValue(idColumn, entity);
                    parameters.put("id", id);
                    if (metadata.logicDeleteColumn() != null) {
                        parameters.put("logicNotDeleteValue", properties.getLogicNotDeleteValue());
                    }
                    String sql = "UPDATE " + metadata.tableName()
                            + " SET " + setSql
                            + " WHERE " + idColumn.name() + " = :id"
                            + logicNotDeleteSql(" AND ");

                    context.setSql(sql);
                    context.setParameters(parameters);
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> r2dbcUtil.updateWithoutLifecycle(context.getSql(), context.getParameters()));
    }

    private Mono<?> selectById(Object id) {
        FieldColumn idColumn = metadata.requireIdColumn();
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectById"), repositoryInterface, new Object[]{id});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName()
                            + " WHERE " + idColumn.name() + " = :id"
                            + logicNotDeleteSql(" AND ");
                    Map<String, Object> parameters = new LinkedHashMap<>();
                    parameters.put("id", id);
                    if (metadata.logicDeleteColumn() != null) {
                        parameters.put("logicNotDeleteValue", properties.getLogicNotDeleteValue());
                    }

                    context.setSql(sql);
                    context.setParameters(parameters);
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> r2dbcUtil.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass())));
    }

    private Flux<?> selectList(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectList"), repositoryInterface, new Object[]{queryWrapper});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    SqlWhere where = buildWhere(queryWrapper);
                    String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName() + where.sql()
                            + orderBySql(queryWrapper)
                            + limitSql(queryWrapper, dialectContext);

                    context.setSql(sql);
                    context.setParameters(where.parameters());
                }));
        return lifecycleExecutor().executeFlux(chain, context, lifecycle,
                () -> r2dbcUtil.queryWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass())));
    }

    private Mono<?> selectOne(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        QueryWrapper<?> wrapper = queryWrapper == null ? new QueryWrapper<>() : queryWrapper.copy();
        if (wrapper.limit() == null) {
            wrapper.limit(1);
        }
        return selectList(wrapper, dialectContext).next();
    }

    private Mono<?> selectPage(Pageable pageable, QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        if (pageable == null) {
            throw new IllegalArgumentException("分页参数不能为空");
        }
        SqlWhere where = buildWhere(queryWrapper);
        String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName() + where.sql()
                + orderBySql(queryWrapper)
                + pageSql(pageable, dialectContext);
        Mono<List<Object>> records = r2dbcUtil.query(sql, where.parameters(), (row, rowMetadata) -> r2dbcUtil.map(row, metadata.entityClass()))
                .cast(Object.class)
                .collectList();
        return Mono.zip(records, selectCount(queryWrapper, dialectContext))
                .map(tuple -> new PageImpl<>(tuple.getT1(), pageable, tuple.getT2()));
    }

    private Mono<Long> selectCount(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectCount"), repositoryInterface, new Object[]{queryWrapper});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    SqlWhere where = buildWhere(queryWrapper);
                    String sql = "SELECT COUNT(1) AS total FROM " + metadata.tableName() + where.sql();

                    context.setSql(sql);
                    context.setParameters(where.parameters());
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> r2dbcUtil.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                                (row, rowMetadata) -> numberValue(row).longValue())
                        .defaultIfEmpty(0L));
    }

    private Mono<Boolean> exists(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        return selectCount(queryWrapper, dialectContext).map(count -> count > 0);
    }

    private SqlWhere buildWhere(QueryWrapper<?> queryWrapper) {
        QueryWrapper<?> wrapper = queryWrapper == null ? new QueryWrapper<>() : queryWrapper;
        Map<String, Object> parameters = new LinkedHashMap<>();
        StringBuilder builder = new StringBuilder();
        boolean hasExplicitLogicDeleteCondition = metadata.logicDeleteColumn() != null
                && wrapper.conditions().stream().anyMatch(condition -> {
                    try {
                        return metadata.columnByName(condition.column()).equals(metadata.logicDeleteColumn());
                    } catch (IllegalArgumentException ignored) {
                        return false;
                    }
                });
        if (metadata.logicDeleteColumn() != null && !hasExplicitLogicDeleteCondition) {
            builder.append(" WHERE ").append(metadata.logicDeleteColumn().name()).append(" = :logicNotDeleteValue");
            parameters.put("logicNotDeleteValue", properties.getLogicNotDeleteValue());
        }
        int index = 0;
        for (QueryWrapper.Condition condition : wrapper.conditions()) {
            FieldColumn column = metadata.columnByName(condition.column());
            String operator = condition.operator();
            Object value = condition.value();
            builder.append(builder.isEmpty() ? " WHERE " : " AND ");
            if (("=".equals(operator) || "<>".equals(operator)) && value == null) {
                builder.append(column.name()).append(" ").append("=".equals(operator) ? "IS NULL" : "IS NOT NULL");
            } else if ("IS NULL".equals(operator) || "IS NOT NULL".equals(operator)) {
                builder.append(column.name()).append(" ").append(operator);
            } else if ("IN".equals(operator) || "NOT IN".equals(operator)) {
                List<?> values = iterableValues(value);
                if (values.isEmpty()) {
                    builder.append("IN".equals(operator) ? "1 = 0" : "1 = 1");
                    continue;
                }
                List<String> markers = new ArrayList<>(values.size());
                for (Object item : values) {
                    String parameterName = "p" + index++;
                    markers.add(":" + parameterName);
                    parameters.put(parameterName, typedNull(item, column.field().getType()));
                }
                builder.append(column.name()).append(" ").append(operator)
                        .append(" (").append(String.join(", ", markers)).append(")");
            } else {
                String parameterName = "p" + index++;
                builder.append(column.name()).append(" ").append(operator).append(" :").append(parameterName);
                parameters.put(parameterName, typedNull(value, column.field().getType()));
            }
        }
        return new SqlWhere(builder.toString(), parameters);
    }

    private List<?> iterableValues(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException("IN 条件值必须是集合");
        }
        List<Object> values = new ArrayList<>();
        iterable.forEach(values::add);
        return values;
    }

    private String orderBySql(QueryWrapper<?> queryWrapper) {
        if (queryWrapper == null || queryWrapper.orderByList().isEmpty()) {
            return "";
        }
        return queryWrapper.orderByList().stream()
                .map(orderBy -> metadata.columnByName(orderBy.column()).name() + (orderBy.asc() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", ", " ORDER BY ", ""));
    }

    private String limitSql(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        if (queryWrapper == null || queryWrapper.limit() == null) {
            return "";
        }
        Long offset = queryWrapper.offset() == null ? null : queryWrapper.offset().longValue();
        return paginationSql(queryWrapper.limit(), offset, dialectContext);
    }

    private String pageSql(Pageable pageable, ContextView dialectContext) {
        if (pageable.isUnpaged()) {
            return "";
        }
        return paginationSql(pageable.getPageSize(), pageable.getOffset(), dialectContext);
    }

    private String paginationSql(long limit, Long offset, ContextView context) {
        return DialectPagination.render(resolveDialect(context), limit, offset);
    }

    private R2dbcDialect resolveDialect(ContextView context) {
        String dataSource = R2dbcDataSourceContext.get(context);
        if (r2dbcUtil.databaseClient().getConnectionFactory() instanceof DynamicRoutingConnectionFactory routing) {
            return DialectResolver.getDialect(routing.getConnectionFactory(dataSource));
        }
        return dialect;
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

    private SqlLifecycleExecutor lifecycleExecutor() {
        return r2dbcUtil.getLifecycleExecutor();
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
            throw new IllegalStateException("方法未找到: " + methodName, e);
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
