package com.wcdk.r2dbc;

import java.lang.annotation.*;

/**
 * 历史仓储注解。
 * @deprecated 自 3.5.16 起请使用 {@link com.wcdk.r2dbc.annotation.Repository}，计划在 4.0.0 移除。
 */
@Deprecated(since = "3.5.16", forRemoval = true)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Repository {
}
