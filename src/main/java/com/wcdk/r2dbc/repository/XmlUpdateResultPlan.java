package com.wcdk.r2dbc.repository;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import org.springframework.core.ResolvableType;
import reactor.core.publisher.Mono;

/** Validates XML write results before any SQL execution and identifies the entity argument. */
record XmlUpdateResultPlan(Class<?> valueType, int entityArgumentIndex) {
    static XmlUpdateResultPlan compile(Method method, Class<?> repositoryInterface) {
        if (method.getReturnType() != Mono.class || !(method.getGenericReturnType() instanceof ParameterizedType)) {
            throw invalid(method, "更新返回值必须声明为 Mono<Long/Integer/Boolean/Void/Entity>");
        }
        var resolved = ResolvableType.forMethodReturnType(method, repositoryInterface).getGeneric(0);
        Class<?> valueType = resolved.resolve();
        if (valueType == null || valueType == Object.class || resolved.hasGenerics()
                || resolved.getType() instanceof java.lang.reflect.WildcardType) {
            throw invalid(method, "更新元素类型必须为明确的标量或实体，不能为原始类型、通配符或嵌套泛型");
        }
        if (valueType == Long.class || valueType == Integer.class || valueType == Boolean.class || valueType == Void.class) {
            return new XmlUpdateResultPlan(valueType, -1);
        }
        int found = -1;
        for (int i = 0; i < method.getParameterCount(); i++) {
            Class<?> input = ResolvableType.forMethodParameter(method, i, repositoryInterface).resolve();
            if (input != null && valueType.isAssignableFrom(input)) {
                if (found >= 0) throw invalid(method, "有多个匹配实体入参，无法确定返回实体位置");
                found = i;
            }
        }
        if (found < 0) throw invalid(method, "返回实体 " + valueType.getName() + " 没有匹配的实体入参");
        return new XmlUpdateResultPlan(valueType, found);
    }
    private static IllegalStateException invalid(Method method, String reason) {
        return new IllegalStateException("XML 更新方法配置错误：" + method.toGenericString() + "；" + reason);
    }
}
