package com.wcdk.r2dbc.core.plan;

import com.wcdk.r2dbc.core.xml.RepositoryStatement;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

/***
 * 启动时编译的不可变仓库方法分发计划。
 * @author wcdk
 */
public record RepositoryMethodPlan(Method method, Kind kind,
                                   RepositoryStatement xmlStatement, String statementId,
                                   StatementDefinition statementDefinition, SqlPlan sqlPlan,
                                   ParameterPlan parameterPlan, ResultMappingPlan resultMappingPlan) {

    public RepositoryMethodPlan(Method method, Kind kind,
                                RepositoryStatement xmlStatement, String statementId) {
        this(method, kind, xmlStatement, statementId,
                new StatementDefinition(kind, statementId), SqlPlan.createDeferred(),
                ParameterPlan.of(method), ResultMappingPlan.of(method, null));
    }

    public RepositoryMethodPlan {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(statementId, "statementId");
        Objects.requireNonNull(statementDefinition, "statementDefinition");
        Objects.requireNonNull(sqlPlan, "sqlPlan");
        Objects.requireNonNull(parameterPlan, "parameterPlan");
        Objects.requireNonNull(resultMappingPlan, "resultMappingPlan");
        if (kind == Kind.XML && xmlStatement == null) {
            throw new IllegalArgumentException("XML method plan requires a statement");
        }
    }

    public static RepositoryMethodPlan enrich(RepositoryMethodPlan plan, Class<?> entityType) {
        return new RepositoryMethodPlan(plan.method(), plan.kind(), plan.xmlStatement(), plan.statementId(),
                plan.statementDefinition(), plan.sqlPlan(), plan.parameterPlan(),
                ResultMappingPlan.of(plan.method(), entityType));
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

    public record ResultMappingPlan(Class<?> returnType, Type genericReturnType,
                                    Class<?> reactiveElementType, Class<?> entityType) {
        public static ResultMappingPlan of(Method method, Class<?> entityType) {
            Class<?> returnType = method.getReturnType();
            Class<?> elementType = null;
            if (reactor.core.publisher.Mono.class.isAssignableFrom(returnType)
                    || reactor.core.publisher.Flux.class.isAssignableFrom(returnType)) {
                Type generic = method.getGenericReturnType();
                if (generic instanceof java.lang.reflect.ParameterizedType parameterized
                        && parameterized.getActualTypeArguments()[0] instanceof Class<?> type) {
                    elementType = type;
                }
            }
            return new ResultMappingPlan(returnType, method.getGenericReturnType(), elementType, entityType);
        }
    }

    public enum Kind {
        OBJECT,
        CRUD,
        XML,
        DERIVED,
        UNSUPPORTED
    }
}