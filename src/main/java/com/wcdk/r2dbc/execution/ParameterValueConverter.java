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
}