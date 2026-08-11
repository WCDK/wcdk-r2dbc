package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.core.xml.RepositoryStatement;

import java.lang.reflect.Method;

/**
 * 启动时编译的不可变仓库方法分发计划。
 *
 * @author WCDK
 **/
public record RepositoryMethodPlan(Method method, Kind kind,
                                   RepositoryStatement xmlStatement, String statementId) {

    public RepositoryMethodPlan {
        java.util.Objects.requireNonNull(method, "method");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(statementId, "statementId");
        if (kind == Kind.XML && xmlStatement == null) {
            throw new IllegalArgumentException("XML method plan requires a statement");
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
