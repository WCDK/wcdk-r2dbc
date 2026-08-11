package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.core.xml.RepositoryXmlRegistry;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 用于不可变 XML 语句分发计划的启动编译器。
 *
 * @author WCDK
 **/
public final class XmlStatementCompiler {
    private final Class<?> repositoryInterface;
    private final RepositoryXmlRegistry registry;

    public XmlStatementCompiler(Class<?> repositoryInterface, RepositoryXmlRegistry registry) {
        this.repositoryInterface = repositoryInterface;
        this.registry = registry;
    }

    public Optional<RepositoryMethodPlan> compile(Method method) {
        return registry.find(repositoryInterface, method.getName())
                .map(statement -> new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.XML,
                        statement, statement.statementId()));
    }
}
