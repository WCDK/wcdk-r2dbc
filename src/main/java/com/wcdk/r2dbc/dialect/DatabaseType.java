package com.wcdk.r2dbc.dialect;

/**
 * 数据库类型。
 * @author wcdk
 */
public enum DatabaseType {
    MYSQL, POSTGRESQL, ORACLE, DM, UNKNOWN;

    public static DatabaseType from(String name) {
        if (name == null) return UNKNOWN;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("mysql")) return MYSQL;
        if (value.contains("postgres")) return POSTGRESQL;
        if (value.contains("oracle")) return ORACLE;
        if (value.contains("dm") || value.contains("dameng")) return DM;
        return UNKNOWN;
    }
}