package com.wcdk.r2dbc.dialect;

/***
 * 数据库类型。
 * @author wcdk
 */
public enum DatabaseType {
    MYSQL, POSTGRESQL, ORACLE, DM, UNKNOWN;

    public static DatabaseType from(String name) {
        if (name == null) return UNKNOWN;
        String value = name.toLowerCase();
        if (value.contains("mysql")) return MYSQL;
        if (value.contains("postgres")) return POSTGRESQL;
        if (value.contains("oracle")) return ORACLE;
        if (value.contains("dm")) return DM;
        return UNKNOWN;
    }
}