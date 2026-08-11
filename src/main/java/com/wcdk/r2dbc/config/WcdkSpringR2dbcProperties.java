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

        void validate(String dataSource) {
            if (maxSize <= 0) {
                throw invalid(dataSource, "max-size must be greater than zero");
            }
            if (initialSize < 0 || initialSize > maxSize) {
                throw invalid(dataSource, "initial-size must be between zero and max-size");
            }
            if (acquireRetry < 0) {
                throw invalid(dataSource, "acquire-retry must not be negative");
            }
            requireNonNegative(dataSource, "max-idle-time", maxIdleTime);
            requireNonNegative(dataSource, "max-life-time", maxLifeTime);
            requireNonNegative(dataSource, "max-acquire-time", maxAcquireTime);
            requireNonNegative(dataSource, "max-create-connection-time", maxCreateConnectionTime);
        }

        private void requireNonNegative(String dataSource, String property, Duration value) {
            if (value == null || value.isNegative()) {
                throw invalid(dataSource, property + " must be a non-negative duration");
            }
        }

        private IllegalArgumentException invalid(String dataSource, String message) {
            return new IllegalArgumentException("Invalid R2DBC pool configuration for '" + dataSource + "': " + message);
        }
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
