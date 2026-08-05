package com.Wcdk.r2dbc.core.xml;

import com.wcdk.r2dbc.core.xml.SqlCommandType;

/**
 * XML 仓储语句定义。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public record RepositoryStatement(String namespace, String id, SqlCommandType commandType, String sql) {

    public String statementId() {
        return namespace + "." + id;
    }
}
