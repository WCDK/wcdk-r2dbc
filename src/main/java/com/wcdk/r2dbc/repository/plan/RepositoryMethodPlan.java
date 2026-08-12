package com.wcdk.r2dbc.repository.plan;

import com.wcdk.r2dbc.repository.RepositoryMethodExecutor;
import com.wcdk.r2dbc.query.xml.RepositoryStatement;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/***
 * Repository 方法统一不可变执行计划。
 * @author wcdk
 **/
public record RepositoryMethodPlan(
        Method method,
        Kind kind,
        RepositoryMethodType methodType,
        SqlCommandType commandType,
        RepositoryStatement xmlStatement,
        String statementId,
        StatementDefinition statementDefinition,
        SqlPlan sqlPlan,
        SqlSource sqlSource,
        ParameterPlan parameterPlan,
        ResultPlan resultPlan,
        RepositoryMethodExecutor executor) {

    public RepositoryMethodPlan(Method method, Kind kind, RepositoryStatement xmlStatement,
                                String statementId) {
        this(method, kind, methodType(kind), commandType(kind, method), xmlStatement, statementId,
                new StatementDefinition(kind, statementId), SqlPlan.createDeferred(),
                new StaticSqlSource(""), ParameterPlan.of(method), ResultPlan.of(method, null), null);
    }

    public RepositoryMethodPlan(Method method, Kind kind, RepositoryStatement xmlStatement,
                                String statementId, StatementDefinition statementDefinition,
                                SqlPlan sqlPlan, ParameterPlan parameterPlan,
                                ResultPlan resultPlan) {
        this(method, kind, methodType(kind), commandType(kind, method), xmlStatement, statementId,
                statementDefinition, sqlPlan, source(sqlPlan), parameterPlan, resultPlan, null);
    }

    public RepositoryMethodPlan {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(methodType, "methodType");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(statementId, "statementId");
        Objects.requireNonNull(statementDefinition, "statementDefinition");
        Objects.requireNonNull(sqlPlan, "sqlPlan");
        Objects.requireNonNull(sqlSource, "sqlSource");
        Objects.requireNonNull(parameterPlan, "parameterPlan");

        Objects.requireNonNull(resultPlan, "resultPlan");
        if (kind == Kind.XML && xmlStatement == null) {
            throw new IllegalArgumentException("XML 方法计划必须包含 XML 语句");
        }
    }

    /***
     * 使用实体类型补全结果映射计划。
     *
     * @param plan 原始方法计划
     * @param entityType 实体类型
     * @return 补全后的不可变计划
     * @author wcdk
     **/
    public static RepositoryMethodPlan enrich(RepositoryMethodPlan plan, Class<?> entityType) {
        ResultPlan mapping = ResultPlan.of(plan.method(), entityType);
        return new RepositoryMethodPlan(plan.method(), plan.kind(), plan.methodType(), plan.commandType(),
                plan.xmlStatement(), plan.statementId(), plan.statementDefinition(), plan.sqlPlan(),
                plan.sqlSource(), plan.parameterPlan(), mapping,
                plan.executor());
    }

    /***
     * 绑定启动阶段解析出的唯一执行器，返回新的不可变计划。
     *
     * @param executor Repository 方法执行器
     * @return 绑定执行器后的计划
     * @author wcdk
     **/
    public RepositoryMethodPlan withExecutor(RepositoryMethodExecutor executor) {
        return new RepositoryMethodPlan(method, kind, methodType, commandType, xmlStatement, statementId,
                statementDefinition, sqlPlan, sqlSource, parameterPlan, resultPlan, executor);
    }
    private static SqlSource source(SqlPlan plan) {
        return new StaticSqlSource(plan.template() == null ? "" : plan.template());
    }

    private static RepositoryMethodType methodType(Kind kind) {
        return RepositoryMethodType.valueOf(kind.name());
    }

    private static SqlCommandType commandType(Kind kind, Method method) {
        if (kind == Kind.OBJECT || kind == Kind.UNSUPPORTED) {
            return SqlCommandType.NONE;
        }
        if (kind == Kind.DERIVED) {
            return SqlCommandType.SELECT;
        }
        String name = method.getName();
        if (name.startsWith("insert")) return SqlCommandType.INSERT;
        if (name.startsWith("delete") || name.startsWith("remove")) return SqlCommandType.DELETE;
        if (name.startsWith("update")) return SqlCommandType.UPDATE;
        return SqlCommandType.SELECT;
    }

    public record StatementDefinition(Kind kind, String statementId) {
    }

    public record SqlPlan(String template, boolean deferred, Object compiledPlan) {
        public static SqlPlan createDeferred() {
            return new SqlPlan(null, true, null);
        }
    }

    public record ParameterPlan(List<ParameterDefinition> parameters) {
        public ParameterPlan {
            parameters = List.copyOf(parameters);
        }

        public static ParameterPlan of(Method method) {
            List<ParameterDefinition> definitions = java.util.stream.IntStream.range(0, method.getParameterCount())
                    .mapToObj(index -> {
                        Parameter parameter = method.getParameters()[index];
                        return new ParameterDefinition(index, parameter.getName(), parameter.getType(), parameter.getParameterizedType());
                    })
                    .toList();
            return new ParameterPlan(definitions);
        }
    }

    public record ParameterDefinition(int index, String name, Class<?> type, Type genericType) {
    }

    /***
     * 兼容旧调用方的结果计划访问方法。
     * @return 统一结果计划
     * @author wcdk
     */
    public ResultPlan resultMappingPlan() {
        return resultPlan;
    }

    /***
     * 统一结果计划工厂。
     * @param method Repository 方法
     * @param entityType 实体类型
     * @return 结果计划
     * @author wcdk
     **/
    public static ResultPlan resultPlan(Method method, Class<?> entityType) {
        return ResultPlan.of(method, entityType);
    }

    public enum Kind {
        OBJECT, CRUD, XML, DERIVED, UNSUPPORTED
    }
}