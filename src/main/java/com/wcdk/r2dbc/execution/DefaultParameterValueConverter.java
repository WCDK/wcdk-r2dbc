package com.wcdk.r2dbc.execution;

/**
 * 默认 R2DBC 参数转换器，枚举按 name() 绑定，其他 Java 原生参数保持不变。
 * @author wcdk
 */
public class DefaultParameterValueConverter implements ParameterValueConverter {

    /**
     * 将枚举转换为 name()，其他 R2DBC 原生 Java 类型原样返回。
     * @author wcdk
     * @param value 原始参数值
     * @return 驱动可绑定的参数值
     */
    @Override
    public Object convert(Object value) {
        return value instanceof Enum<?> enumValue ? enumValue.name() : value;
    }
    @Override
    public Class<?> nullType(Class<?> javaType) {
        return javaType.isEnum() ? String.class : javaType;
    }

}