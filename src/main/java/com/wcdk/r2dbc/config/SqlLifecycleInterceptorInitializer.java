package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptor;
import com.wcdk.r2dbc.core.interceptor.SqlLifecycleInterceptorHolder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SQL生命周期拦截器初始化器。
 * <p>
 * 在应用启动时初始化拦截器链。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class SqlLifecycleInterceptorInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqlLifecycleInterceptorInitializer.class);

    private final List<SqlLifecycleInterceptor> interceptors;

    public SqlLifecycleInterceptorInitializer(List<SqlLifecycleInterceptor> interceptors) {
        this.interceptors = interceptors;
    }

    @PostConstruct
    public void init() {
        SqlLifecycleInterceptorHolder.init(interceptors, null);
        if (interceptors != null && !interceptors.isEmpty()) {
            log.info("Initialized {} SQL lifecycle interceptors: {}",
                    interceptors.size(),
                    interceptors.stream().map(i -> i.getClass().getSimpleName()).toList());
        } else {
            log.debug("No SQL lifecycle interceptors configured");
        }
    }
}
