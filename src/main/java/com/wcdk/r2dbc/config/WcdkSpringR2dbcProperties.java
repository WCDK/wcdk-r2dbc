package com.wcdk.r2dbc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @auther WCDK
 * @date 2026/7/27
 * @version 1.0
 **/
@Data
@ConfigurationProperties(prefix = "spring.r2dbc")
public class WcdkSpringR2dbcProperties {

    private String url;

    private String username;

    private String password;

    private Map<String, String> properties = new LinkedHashMap<>();

    private String primary = "master";

    private Map<String, DataSourceProperties> dataSources = new LinkedHashMap<>();

    private Pool pool = new Pool();

    @Data
    public static class Pool {

        private boolean enabled = true;

        private int maxSize = 20;

        private Duration maxIdleTime = Duration.ofSeconds(30);

        private Duration maxLifeTime = Duration.ofSeconds(1800);

        private Duration maxAcquireTime = Duration.ofSeconds(10);

        private int acquireRetry = 1;

        private Duration maxCreateConnectionTime = Duration.ofSeconds(10);

        private int initialSize = 10;

        private String validationQuery;
    }

    @Data
    public static class DataSourceProperties {

        private String url;

        private String username;

        private String password;

        private Map<String, String> properties = new LinkedHashMap<>();

        private Pool pool;
    }
}
