package com.wcdk.r2dbc.repository;

/** 统一定义派生条件支持的算子后缀及参数数量。 */
public enum DerivedOperator {
    IS_NOT_NULL("IsNotNull", "IS NOT NULL", 0), IS_NULL("IsNull", "IS NULL", 0),
    GREATER_THAN_EQUAL("GreaterThanEqual", ">=", 1), LESS_THAN_EQUAL("LessThanEqual", "<=", 1),
    GREATER_THAN("GreaterThan", ">", 1), LESS_THAN("LessThan", "<", 1),
    NOT_IN("NotIn", "NOT IN", 1), LIKE("Like", "LIKE", 1), IN("In", "IN", 1),
    BETWEEN("Between", "BETWEEN", 2), NOT("Not", "<>", 1), EQUAL("", "=", 1);

    private final String suffix;
    private final String sql;
    private final int argumentCount;
    DerivedOperator(String suffix, String sql, int argumentCount) {
        this.suffix = suffix; this.sql = sql; this.argumentCount = argumentCount;
    }
    public String suffix() { return suffix; }
    public String sql() { return sql; }
    public int argumentCount() { return argumentCount; }
}
