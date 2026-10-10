package com.wcdk.r2dbc.annotation;

import java.lang.annotation.*;

/**
 * Explicit database column for a mapping constructor parameter.
 * Field annotations continue to use Spring Data's Column annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Documented
public @interface Column {
    String value();
}
