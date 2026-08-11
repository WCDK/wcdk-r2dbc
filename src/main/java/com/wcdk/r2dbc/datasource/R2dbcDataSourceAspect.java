package com.wcdk.r2dbc.datasource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;

/***
 * 数据源切换切面。
 * @author wcdk
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class R2dbcDataSourceAspect {

    @Around("@within(com.wcdk.r2dbc.datasource.R2dbcDataSource) || @annotation(com.wcdk.r2dbc.datasource.R2dbcDataSource)")
    public Object switchDataSource(ProceedingJoinPoint joinPoint) throws Throwable {
        R2dbcDataSource dataSource = findDataSource(joinPoint);
        if (dataSource == null) {
            return joinPoint.proceed();
        }
        String key = dataSource.value();
        Class<?> returnType = ((MethodSignature) joinPoint.getSignature()).getReturnType();
        if (Mono.class.isAssignableFrom(returnType)) {
            return R2dbcDataSourceContext.use(key, Mono.defer(() -> proceedMono(joinPoint)));
        }
        if (Flux.class.isAssignableFrom(returnType)) {
            return R2dbcDataSourceContext.use(key, Flux.defer(() -> proceedFlux(joinPoint)));
        }
        if (Publisher.class.isAssignableFrom(returnType)) {
            return R2dbcDataSourceContext.use(key, Flux.defer(() -> proceedFlux(joinPoint)));
        }
        throw new IllegalStateException("数据源切换方法必须返回Publisher: " + ((MethodSignature) joinPoint.getSignature()).getMethod().toGenericString());
    }

    private Mono<?> proceedMono(ProceedingJoinPoint joinPoint) {
        try {
            Object result = joinPoint.proceed();
            if (!(result instanceof Publisher<?> publisher)) {
                return Mono.error(new IllegalStateException("数据源切换方法必须返回Publisher"));
            }
            return Mono.from(publisher);
        } catch (Throwable error) {
            return Mono.error(error);
        }
    }

    private Flux<?> proceedFlux(ProceedingJoinPoint joinPoint) {
        try {
            Object result = joinPoint.proceed();
            if (!(result instanceof Publisher<?> publisher)) {
                return Flux.error(new IllegalStateException("数据源切换方法必须返回Publisher"));
            }
            return Flux.from(publisher);
        } catch (Throwable error) {
            return Flux.error(error);
        }
    }

    private R2dbcDataSource findDataSource(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        R2dbcDataSource dataSource = AnnotationUtils.findAnnotation(method, R2dbcDataSource.class);
        if (dataSource != null) {
            return dataSource;
        }
        Method targetMethod = targetMethod(joinPoint, method);
        if (targetMethod != method) {
            dataSource = AnnotationUtils.findAnnotation(targetMethod, R2dbcDataSource.class);
            if (dataSource != null) {
                return dataSource;
            }
        }
        return AnnotationUtils.findAnnotation(targetMethod.getDeclaringClass(), R2dbcDataSource.class);
    }

    private Method targetMethod(ProceedingJoinPoint joinPoint, Method method) {
        try {
            return joinPoint.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException ignored) {
            return method;
        }
    }
}
