package com.wcdk.r2dbc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @auther WCDK
 * @date 2026/7/20
 * @version 1.0
 **/
@Data
@ConfigurationProperties(prefix = "wcdk.r2dbc")
public class WcdkR2dbcProperties {

    private boolean enabled;

    private boolean sqlLogEnabled = true;

    private boolean snowflakeId;


    private boolean quoteIdentifier = true;

    private String mapperLocations = "classpath*:repository/**/*.xml";

    private String logicDeleteField = "delFlg";

    private Object logicNotDeleteValue = 0;

    private Object logicDeleteValue = 1;
}
