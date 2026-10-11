package com.wcdk.r2dbc.execution;

import java.util.Locale;

/** XML、仓储计划和执行生命周期共享的 SQL 命令类型。 */
public enum SqlCommandType {
    INSERT, UPDATE, DELETE, SELECT, MERGE, UNKNOWN;

    public static SqlCommandType fromMethodName(String methodName) {
        if (methodName == null) return UNKNOWN;
        String name = methodName.toLowerCase(Locale.ROOT);
        if (name.startsWith("insert")) return INSERT;
        if (name.startsWith("update")) return UPDATE;
        if (name.startsWith("delete") || name.startsWith("remove")) return DELETE;
        if (name.startsWith("merge")) return MERGE;
        if (name.startsWith("find") || name.startsWith("select") || name.startsWith("count")
                || name.startsWith("exists") || name.startsWith("query")) return SELECT;
        return UNKNOWN;
    }
}
