package com.wcdk.r2dbc.execution;

/***
 * 默认 R2DBC 参数转换器，保持 Java 原生参数类型不变。
 * @author wcdk
 **/
public class DefaultParameterValueConverter implements ParameterValueConverter {

    /***
     * 原样返回 R2DBC 原生 Java 类型。
     * @author wcdk
     * @param value 原始参数值
     * @return 原始参数值
     */
    @Override
    public Object convert(Object value) {
        return value;
    }
}