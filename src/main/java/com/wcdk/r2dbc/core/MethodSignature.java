package com.wcdk.r2dbc.core;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/***
 * Repository 方法完整签名。
 * @author wcdk
 */
public record MethodSignature(String name, List<Class<?>> parameterTypes, Class<?> returnType) {
    public static MethodSignature of(Method method) {
        return new MethodSignature(method.getName(), List.copyOf(Arrays.asList(method.getParameterTypes())),
                method.getReturnType());
    }
}
