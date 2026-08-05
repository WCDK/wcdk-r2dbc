package com.wcdk.r2dbc.core.log;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.ContextView;

import java.util.Map;

/**
 * R2DBC SQL日志记录器。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class R2dbcSqlLogger {

    private static final Logger log = LoggerFactory.getLogger(R2dbcSqlLogger.class);

    private final WcdkR2dbcProperties properties;

    private final WcdkSpringR2dbcProperties springR2dbcProperties;

    public R2dbcSqlLogger(WcdkR2dbcProperties properties, WcdkSpringR2dbcProperties springR2dbcProperties) {
        this.properties = properties == null ? new WcdkR2dbcProperties() : properties;
        this.springR2dbcProperties = springR2dbcProperties == null ? new WcdkSpringR2dbcProperties() : springR2dbcProperties;
    }

    /**
     * 记录SQL日志。
     *
     * @param contextView 上下文视图
     * @param sql         SQL语句
     * @param parameters  参数
     */
    public void logSql(ContextView contextView, String sql, Map<?, ?> parameters) {
        if (!properties.isSqlLogEnabled()) {
            return;
        }
        String dataSource = currentDataSource(contextView);
        if (parameters == null || parameters.isEmpty()) {
            log.info("=================R2DBC==========START==========");
            log.info("数据源：{}", dataSource);
            log.info("执行SQL：{}", normalizeSql(sql));
            log.info("=================R2DBC==========END==========");
            return;
        }
        log.info("=================R2DBC==========START==========");
        log.info("数据源：{}", dataSource);
        log.info("执行SQL：{}", normalizeSql(sql));
        log.info("参数：{}", parameters);
        log.info("=================R2DBC==========END==========");
    }

    /**
     * 获取当前数据源名称。
     *
     * @param contextView 上下文视图
     * @return 数据源名称
     */
    public String currentDataSource(ContextView contextView) {
        String dataSource = R2dbcDataSourceContext.get(contextView);
        if (dataSource != null && !dataSource.isBlank()) {
            return dataSource;
        }
        String primary = springR2dbcProperties.getPrimary();
        return primary == null || primary.isBlank() ? "master" : primary;
    }

    /**
     * 格式化SQL（去除多余空格）。
     *
     * @param sql SQL语句
     * @return 格式化后的SQL
     */
    public String normalizeSql(String sql) {
        return sql.strip().replaceAll("\\s+", " ");
    }
}
