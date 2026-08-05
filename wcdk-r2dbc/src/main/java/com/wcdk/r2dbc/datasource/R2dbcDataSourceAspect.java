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

/**
 * @auther WCDK
 * @date 2026/7/27
 * @version 1.0
 **/
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class R2dbcDataSourceAspect {

    @Around("@within(com.Wcdk.r2dbc.datasource.R2dbcDataSource) || @annotation(com.Wcdk.r2dbc.datasource.R2dbcDataSource)")
    public Object switchDataSource(ProceedingJoinPoint joinPoint) throws Throwable {
        R2dbcDataSource dataSource = findDataSource(joinPoint);
        Object result = joinPoint.proceed();
        if (dataSource == null || result == null) {
            return result;
        }
        String key = dataSource.value();
        if (result instanceof Mono<?> mono) {
            return R2dbcDataSourceContext.use(key, mono);
        }
        if (result instanceof Flux<?> flux) {
            return R2dbcDataSourceContext.use(key, flux);
        }
        if (result instanceof Publisher<?> publisher) {
            return R2dbcDataSourceContext.use(key, Flux.from(publisher));
        }
        return result;
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
