package com.wcdk.r2dbc.config;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables qxtd R2DBC repository proxy scanning.
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(WcdkR2dbcRepositoryRegistrar.class)
public @interface EnableWcdkR2dbcRepositories {

    String[] basePackages() default {};

    Class<?>[] basePackageClasses() default {};
}
