package com.wcdk.r2dbc.core.datasource;

import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC数据源路由器。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class R2dbcDataSourceRouter {

    /**
     * 在指定数据源上执行Mono操作。
     *
     * @param dataSource 数据源名称
     * @param publisher  操作
     * @param <T>        返回类型
     * @return 操作结果
     */
    public <T> Mono<T> dataSource(String dataSource, Mono<T> publisher) {
        return R2dbcDataSourceContext.use(dataSource, publisher);
    }

    /**
     * 在指定数据源上执行Flux操作。
     *
     * @param dataSource 数据源名称
     * @param publisher  操作
     * @param <T>        返回类型
     * @return 操作结果
     */
    public <T> Flux<T> dataSource(String dataSource, Flux<T> publisher) {
        return R2dbcDataSourceContext.use(dataSource, publisher);
    }
}
