package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;

/***
 * R2DBC 数据库方言接口。
 * @author wcdk
 */
public interface DatabaseDialect {
    DatabaseType databaseType();

    String quote(String identifier);

    default String quoteIdentifier(String identifier) {
        return quote(identifier);
    }

    String pagination(String sql, long offset, long limit);

    String renderLimitOffset(Integer limit, Long offset);

    /*** 在收窄到兼容的 Integer 接口之前检查范围，offset 保持 long。 @author wcdk ***/
    default String renderLimitOffset(long limit, Long offset) {
        if (limit <= 0 || limit > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页 limit 必须在 1 到 Integer.MAX_VALUE 之间");
        }
        if (offset != null && offset < 0) {
            throw new IllegalArgumentException("分页 offset 不能为负数");
        }
        return renderLimitOffset(Integer.valueOf((int) limit), offset);
    }

    String renderGeneratedKey(String... columns);

    /** Column argument expected by the driver when requesting generated values. */
    default String generatedValueColumn(String rawColumn, String renderedColumn) {
        return rawColumn;
    }

    /*** 插入没有业务字段的记录；idColumn 为已引用的生成主键列名。 @author wcdk ***/
    default String emptyInsert(String table, String idColumn) {
        return "INSERT INTO " + table + " DEFAULT VALUES";
    }

    /*** 将短路存在性子查询包装为数值 1/0，避免数据库 BOOLEAN 类型差异。 @author wcdk ***/
    default String existsResult(String query) {
        return "SELECT CASE WHEN EXISTS (" + query + ") THEN 1 ELSE 0 END";
    }


    default boolean supportsSavepoint() {
        return false;
    }

    default GeneratedKeyStrategy generatedKeyStrategy() {
        return supportsReturning() ? GeneratedKeyStrategy.RETURNING : GeneratedKeyStrategy.NONE;
    }

    boolean supportsReturning();

    boolean supportsUpsert();

    boolean supports(ConnectionFactory connectionFactory);
}
