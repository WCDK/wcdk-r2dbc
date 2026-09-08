package com.wcdk.r2dbc.execution;

/***
 * R2DBC 参数值转换器。
 * @author wcdk
 **/
public interface ParameterValueConverter {

    /***
     * 转换驱动需要兼容的参数值。
     * @author wcdk
     * @param value 原始参数值
     * @return 绑定参数值
     */
    Object convert(Object value);

    /**
     * 转换空参数绑定时使用的 Java 类型，默认保持原类型。
     *
     * @param javaType 原始参数 Java 类型
     * @return 驱动实际支持的空参数类型
     */
    default Class<?> nullType(Class<?> javaType) {
        return javaType;
    }
}
