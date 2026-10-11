package com.wcdk.r2dbc.annotation;

import java.lang.annotation.*;

/**
 * 显式指定映射构造器参数对应的数据库列。
 * 字段上的列注解仍使用 Spring Data 的 Column 注解。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Documented
public @interface Column {
    String value();
}
