package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.core.xml.RepositoryXmlRegistry;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/***
 * 在创建代理时一次性编译仓库方法分类。
 * @author wcdk
 */
public final class RepositoryMethodPlanCompiler {

    private final Class<?> repositoryInterface;
    private final RepositoryMetadata metadata;
    private final CrudStatementCompiler crudCompiler;
    private final XmlStatementCompiler xmlCompiler;
    private final DerivedMethodCompiler derivedCompiler;

    public RepositoryMethodPlanCompiler(Class<?> repositoryInterface, RepositoryMetadata metadata,
                                        RepositoryXmlRegistry xmlRegistry, WcdkR2dbcProperties properties) {
        this.repositoryInterface = java.util.Objects.requireNonNull(repositoryInterface);
        java.util.Objects.requireNonNull(xmlRegistry);
        this.metadata = metadata;
        this.crudCompiler = new CrudStatementCompiler(repositoryInterface, metadata);
        this.xmlCompiler = new XmlStatementCompiler(repositoryInterface, xmlRegistry);
        this.derivedCompiler = new DerivedMethodCompiler(repositoryInterface, metadata, properties);
    }

    public Map<Method, RepositoryMethodPlan> compile() {
        Map<Method, RepositoryMethodPlan> plans = new LinkedHashMap<>();
        for (Method method : repositoryInterface.getMethods()) {
            plans.put(method, enrich(compile(method)));
        }
        try {
            for (Method method : new Method[]{Object.class.getMethod("toString"),
                    Object.class.getMethod("hashCode"), Object.class.getMethod("equals", Object.class)}) {
                plans.putIfAbsent(method, enrich(new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.OBJECT,
                        null, repositoryInterface.getName() + "." + method.getName())));
            }
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException("JDK Object methods are unavailable", impossible);
        }
        return Map.copyOf(plans);
    }

    private RepositoryMethodPlan enrich(RepositoryMethodPlan plan) {
        return RepositoryMethodPlan.enrich(plan, metadata == null ? null : metadata.entityClass());
    }

    private RepositoryMethodPlan compile(Method method) {
        return crudCompiler.compile(method)
                .or(() -> xmlCompiler.compile(method))
                .or(() -> derivedCompiler.compile(method))
                .orElseGet(() -> new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.UNSUPPORTED,
                        null, repositoryInterface.getName() + "." + method.getName()));
    }

    static boolean isObjectMethod(String name) {
        return "toString".equals(name) || "hashCode".equals(name) || "equals".equals(name);
    }

    static boolean isCrudMethod(String name) {
        return switch (name) {
            case "insert", "deleteById", "updateById", "selectById", "findAll", "selectList",
                 "selectPage", "selectOne", "selectCount", "exists" -> true;
            default -> false;
        };
    }
}