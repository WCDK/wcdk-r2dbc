package com.wcdk.r2dbc.repository.plan;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

/***
 * Repository 结果映射统一计划。
 * 启动阶段确定结果形态，执行阶段只负责按计划映射数据。
 * @author wcdk
 */
public record ResultPlan(Class<?> returnType,
                         Type genericReturnType,
                         Class<?> elementType,
                         Class<?> entityType,
                         ResultShape shape) {

    /***
     * 结果形态。
     * @author wcdk
     */
    public enum ResultShape {
        SCALAR, ENTITY, DTO, MAP, OPTIONAL, MONO, FLUX, PAGE
    }

    /***
     * 从方法签名创建结果计划。
     *
     * @param method Repository 方法
     * @param entityType 实体类型
     * @return 结果计划
     * @author wcdk
     */
    public static ResultPlan of(java.lang.reflect.Method method, Class<?> entityType) {
        Class<?> returnType = method.getReturnType();
        Type genericReturnType = method.getGenericReturnType();
        Class<?> elementType = genericClass(genericReturnType);
        ResultShape shape = shape(returnType, elementType, entityType);
        return new ResultPlan(returnType, genericReturnType, elementType, entityType, shape);
    }

    /***
     * 判断结果是否为响应式流。
     * @return 是否为 Mono 或 Flux
     * @author wcdk
     */
    /***
     * 兼容旧结果访问命名。
     * @return 响应式元素类型
     * @author wcdk
     */
    public Class<?> reactiveElementType() {
        return elementType;
    }

    public boolean reactive() {
        return shape == ResultShape.MONO || shape == ResultShape.FLUX;
    }

    private static ResultShape shape(Class<?> returnType, Class<?> elementType, Class<?> entityType) {
        if (reactor.core.publisher.Mono.class.isAssignableFrom(returnType)) return ResultShape.MONO;
        if (reactor.core.publisher.Flux.class.isAssignableFrom(returnType)) return ResultShape.FLUX;
        if (org.springframework.data.domain.Page.class.isAssignableFrom(returnType)) return ResultShape.PAGE;
        if (Optional.class.isAssignableFrom(returnType)) return ResultShape.OPTIONAL;
        if (Map.class.isAssignableFrom(returnType)) return ResultShape.MAP;
        if (entityType != null && entityType != Object.class) {
            return entityType.equals(elementType) || entityType.equals(returnType)
                    ? ResultShape.ENTITY : ResultShape.DTO;
        }
        return ResultShape.SCALAR;
    }

    private static Class<?> genericClass(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type candidate = parameterized.getActualTypeArguments()[0];
            if (candidate instanceof Class<?> clazz) return clazz;
            if (candidate instanceof ParameterizedType nested
                    && nested.getRawType() instanceof Class<?> clazz) return clazz;
        }
        return null;
    }
}