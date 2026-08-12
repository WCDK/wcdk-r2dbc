package com.wcdk.r2dbc.execution;

/**
 * 将驱动返回值转换为实体属性类型。
 *
 * @author WCDK
 **/
public interface R2dbcValueConverter {

    boolean supports(Class<?> sourceType, Class<?> targetType);

    Object convert(Object value, Class<?> targetType);
}
