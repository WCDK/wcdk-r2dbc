package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.dialect.DatabaseDialect;

/***
 * 业务数据库方言分页 SQL 工具。
 * @author wcdk
 */
final class DialectPagination {

    private DialectPagination() {
    }

    /***
     * 根据统一业务方言生成分页子句。
     * @param dialect 数据库方言
     * @param limit 分页大小
     * @param offset 偏移量
     * @return 分页 SQL 子句
     * @author wcdk
     */
    static String render(DatabaseDialect dialect, long limit, Long offset) {
        String clause = dialect.renderLimitOffset((int) limit, offset);
        return clause == null || clause.isBlank() ? "" : " " + clause;
    }
}