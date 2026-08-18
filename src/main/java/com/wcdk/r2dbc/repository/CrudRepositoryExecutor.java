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
 * CRUD Repository 执行器。
 * @author wcdk
 */
final class CrudRepositoryExecutor implements RepositoryMethodExecutor {
    private final WcdkR2dbcProperties properties;
    private final RepositoryMetadata metadata;
    private final Class<?> repositoryInterface;
    private final RepositoryXmlRegistry repositoryXmlRegistry;
    private final DatabaseDialect dialect;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final boolean snowflakeIdEnabled;
    private final CustomMethodResolver customMethodResolver;
    private final SqlExecutionEngine sqlExecutionEngine;
    private final RepositoryParameterBinder parameterBinder;
    private final RepositoryQuerySqlBuilder querySqlBuilder;

    CrudRepositoryExecutor(WcdkR2dbcProperties properties, RepositoryMetadata metadata, Class<?> repositoryInterface, RepositoryXmlRegistry repositoryXmlRegistry, DatabaseDialect dialect, SnowflakeIdGenerator snowflakeIdGenerator, SqlExecutionEngine sqlExecutionEngine, RepositoryParameterBinder parameterBinder) {
        this.properties = properties;
        this.metadata = metadata;
        this.repositoryInterface = repositoryInterface;
        this.repositoryXmlRegistry = repositoryXmlRegistry;
        this.dialect = dialect;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.snowflakeIdEnabled = properties.isSnowflakeId();
        this.customMethodResolver = metadata == null ? null : new CustomMethodResolver(metadata, properties.getLogicDeleteValue(), properties.getLogicNotDeleteValue());
        this.sqlExecutionEngine = sqlExecutionEngine;
        this.parameterBinder = parameterBinder;
        this.querySqlBuilder = new RepositoryQuerySqlBuilder(properties, metadata, dialect, sqlExecutionEngine);
    }

    @Override
    public boolean supports(RepositoryMethodPlan plan) {
        return plan.kind() == RepositoryMethodPlan.Kind.CRUD && !isQueryMethod(plan.method().getName());
    }

    @Override
    public Object execute(RepositoryMethodPlan plan, Object[] args, ContextView context, Object proxy) {
        return executeCrudPlan(plan, args, context);
    }

    Object executeQueryPlan(RepositoryMethodPlan plan, Object[] arguments, ContextView context) {
        return switch (plan.method().getName()) {
            case "findAll" -> selectList(new QueryWrapper<>(), context);
            case "selectList" -> selectList(querySqlBuilder.queryWrapper(arguments), context);
            case "selectPage" -> selectPage(querySqlBuilder.pageable(arguments), querySqlBuilder.queryWrapper(arguments, 1), context);
            case "selectOne" -> selectOne(querySqlBuilder.queryWrapper(arguments), context);
            case "selectCount" -> selectCount(querySqlBuilder.queryWrapper(arguments), context);
            case "exists" -> exists(querySqlBuilder.queryWrapper(arguments), context);
            default -> throw new UnsupportedOperationException("不支持的查询方法: " + plan.method().getName());
        };
    }

    private boolean isQueryMethod(String methodName) {
        return methodName.equals("findAll")
                || (methodName.startsWith("select") && !methodName.equals("selectById"))
                || methodName.equals("exists");
    }
    Object executeCrudPlan(RepositoryMethodPlan plan, Object[] arguments, ContextView context) {
        Method method = plan.method();
        String methodName = method.getName();
        if (metadata == null && RepositoryMethodPlanCompiler.isCrudMethod(method)) {
            throw new UnsupportedOperationException("Repository接口未继承 BaseRepository，不支持基础 CRUD 方法: " + methodName);
        }
        return switch (methodName) {
            case "insert" -> insert(arguments[0]);
            case "deleteById" -> deleteById(arguments[0]);
            case "updateById" -> updateById(arguments[0]);
            case "selectById" -> selectById(arguments[0]);
            case "findAll" -> selectList(new QueryWrapper<>(), context);
            case "selectList" -> selectList(querySqlBuilder.queryWrapper(arguments), context);
            case "selectPage" ->  selectPage(querySqlBuilder.pageable(arguments), querySqlBuilder.queryWrapper(arguments, 1), context);
            case "selectOne" -> selectOne(querySqlBuilder.queryWrapper(arguments), context);
            case "selectCount" -> selectCount(querySqlBuilder.queryWrapper(arguments), context);
            case "exists" -> exists(querySqlBuilder.queryWrapper(arguments), context);
            default -> throw new UnsupportedOperationException("不支持的 CRUD 方法: " + methodName);
        };
    }

    private Number numberValue(Row row) {
        Object value = row.get(0);
        if (!(value instanceof Number number)) throw new IllegalStateException("查询结果不是数字: " + value);
        return number;
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
                    List<FieldColumn> insertColumns = metadata.columns().stream()
                            .filter(column -> fieldValue(column, entity) != null)
                            .toList();
                    String sql;
                    if (insertColumns.isEmpty()) {
                        sql = "INSERT INTO " + metadata.tableName() + " DEFAULT VALUES";
                    } else {
                        String fields = insertColumns.stream().map(FieldColumn::name).collect(Collectors.joining(", "));
                        String values = insertColumns.stream()
                                .peek(column -> parameters.put(column.field().getName(), fieldValue(column, entity)))
                                .map(column -> ":" + column.field().getName())
                                .collect(Collectors.joining(", "));
                        sql = "INSERT INTO " + metadata.tableName() + " (" + fields + ") VALUES (" + values + ")";
                    }

                    context.setSql(sql);
                    context.setParameters(parameters);
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.updateWithoutLifecycle(context.getSql(), context.getParameters())
                        .thenReturn(entity));
    }

    private Mono<Long> deleteById(Object id) {
        FieldColumn idColumn = metadata.requireIdColumn();
        Object deleteId = normalizeDeleteId(id, idColumn);
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("deleteById"), repositoryInterface, new Object[]{deleteId});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    FieldColumn logicDeleteColumn = metadata.logicDeleteColumn();
                    String sql;
                    Map<String, Object> parameters;
                    if (logicDeleteColumn != null) {
                        sql = "UPDATE " + metadata.tableName()
                                + " SET " + logicDeleteColumn.name() + " = :logicDeleteValue"
                                + " WHERE " + idColumn.name() + " = :id"
                                + querySqlBuilder.logicNotDeleteSql(" AND ");
                        parameters = Map.of(
                                "id", deleteId,
                                "logicDeleteValue", LogicDeleteValueConverter.convert(
                                        properties.getLogicDeleteValue(), logicDeleteColumn.field().getType()),
                                "logicNotDeleteValue", LogicDeleteValueConverter.convert(
                                        properties.getLogicNotDeleteValue(), logicDeleteColumn.field().getType()));
                    } else {
                        sql = "DELETE FROM " + metadata.tableName() + " WHERE " + idColumn.name() + " = :id";
                        parameters = Map.of("id", deleteId);
                    }

                    context.setSql(sql);
                    context.setParameters(parameters);
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.updateWithoutLifecycle(context.getSql(), context.getParameters()));
    }

    /**
     * 兼容 deleteById(entity) 方法引用，实体参数自动提取其 @Id 字段。
     *
     * @param value 删除参数，可以是主键值或实体对象
     * @param idColumn 实体主键字段
     * @return SQL 使用的主键值
     */
    private Object normalizeDeleteId(Object value, FieldColumn idColumn) {
        if (value == null || !metadata.entityClass().isInstance(value)) {
            return value;
        }
        return fieldValue(idColumn, value);
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
                        parameters.put("logicNotDeleteValue", LogicDeleteValueConverter.convert(
                                properties.getLogicNotDeleteValue(), metadata.logicDeleteColumn().field().getType()));
                    }
                    String sql = "UPDATE " + metadata.tableName()
                            + " SET " + setSql
                            + " WHERE " + idColumn.name() + " = :id"
                            + querySqlBuilder.logicNotDeleteSql(" AND ");

                    context.setSql(sql);
                    context.setParameters(parameters);
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.updateWithoutLifecycle(context.getSql(), context.getParameters()));
    }

    private Mono<?> selectById(Object id) {
        FieldColumn idColumn = metadata.requireIdColumn();
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectById"), repositoryInterface, new Object[]{id});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    String sql = "SELECT " + querySqlBuilder.selectColumns() + " FROM " + metadata.tableName()
                            + " WHERE " + idColumn.name() + " = :id"
                            + querySqlBuilder.logicNotDeleteSql(" AND ");
                    Map<String, Object> parameters = new LinkedHashMap<>();
                    parameters.put("id", id);
                    if (metadata.logicDeleteColumn() != null) {
                        parameters.put("logicNotDeleteValue", LogicDeleteValueConverter.convert(
                                properties.getLogicNotDeleteValue(), metadata.logicDeleteColumn().field().getType()));
                    }

                    context.setSql(sql);
                    context.setParameters(parameters);
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> sqlExecutionEngine.map(row, metadata.entityClass())));
    }

    private Flux<?> selectList(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectList"), repositoryInterface, new Object[]{queryWrapper});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    RepositoryQuerySqlBuilder.SqlWhere where = querySqlBuilder.buildWhere(queryWrapper);
                    String sql = "SELECT " + querySqlBuilder.selectColumns() + " FROM " + metadata.tableName() + where.sql()
                            + querySqlBuilder.orderBySql(queryWrapper)
                            + querySqlBuilder.limitSql(queryWrapper, dialectContext);

                    context.setSql(sql);
                    context.setParameters(where.parameters());
                }));
        return lifecycleExecutor().executeFlux(chain, context, lifecycle,
                () -> sqlExecutionEngine.queryWithoutLifecycle(context.getSql(), context.getParameters(),
                        (row, rowMetadata) -> sqlExecutionEngine.map(row, metadata.entityClass())));
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
        RepositoryQuerySqlBuilder.SqlWhere where = querySqlBuilder.buildWhere(queryWrapper);
        String sql = "SELECT " + querySqlBuilder.selectColumns() + " FROM " + metadata.tableName() + where.sql()
                + querySqlBuilder.orderBySql(queryWrapper)
                + querySqlBuilder.pageSql(pageable, dialectContext);
        Mono<List<Object>> records = sqlExecutionEngine.query(sql, where.parameters(), (row, rowMetadata) -> sqlExecutionEngine.map(row, metadata.entityClass()))
                .cast(Object.class)
                .collectList();
        // 先完成 count，再订阅 records，避免事务内同一连接并发执行 statement。
        return selectCount(queryWrapper, dialectContext)
                .flatMap(total -> records
                        .map(items -> new PageImpl<>(items, pageable, total)));
    }

    private Mono<Long> selectCount(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        SqlLifecycleInterceptorChain chain = lifecycleExecutor().getChain();
        SqlExecutionContext context = new SqlExecutionContext(
                findMethod("selectCount"), repositoryInterface, new Object[]{queryWrapper});

        Mono<Boolean> lifecycle = lifecycleExecutor().prepare(chain, context,
                () -> Mono.fromRunnable(() -> {
                    RepositoryQuerySqlBuilder.SqlWhere where = querySqlBuilder.buildWhere(queryWrapper);
                    String sql = "SELECT COUNT(1) AS total FROM " + metadata.tableName() + where.sql();

                    context.setSql(sql);
                    context.setParameters(where.parameters());
                }));
        return lifecycleExecutor().executeMono(chain, context, lifecycle,
                () -> sqlExecutionEngine.queryOneWithoutLifecycle(context.getSql(), context.getParameters(),
                                (row, rowMetadata) -> numberValue(row).longValue())
                        .defaultIfEmpty(0L));
    }

    private Mono<Boolean> exists(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        return selectCount(queryWrapper, dialectContext).map(count -> count > 0);
    }

    private SqlLifecycleExecutor lifecycleExecutor() {
        return sqlExecutionEngine.lifecycleExecutor();
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
