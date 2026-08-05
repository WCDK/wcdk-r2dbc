package com.wcdk.r2dbc.core.xml;

/**
 * XML 仓储语句定义。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public record RepositoryStatement(String namespace, String id, SqlCommandType commandType, String sql,
                                   String resultType, String resultMapId) {

    public RepositoryStatement(String namespace, String id, SqlCommandType commandType, String sql) {
        this(namespace, id, commandType, sql, null, null);
    }

    public String statementId() {
        return namespace + "." + id;
    }
}
