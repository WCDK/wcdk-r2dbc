package com.wcdk.r2dbc.repository.plan;

/***
 * Repository SQL 来源统一接口。
 * @author wcdk
 **/
public interface SqlSource {

    /***
     * 根据运行时参数生成绑定 SQL。
     *
     * @param arguments Repository 方法参数
     * @return 绑定 SQL
     * @author wcdk
     **/
    BoundSql getBoundSql(Object[] arguments);
}