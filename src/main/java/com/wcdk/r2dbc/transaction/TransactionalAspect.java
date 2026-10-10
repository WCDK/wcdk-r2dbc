package com.wcdk.r2dbc.transaction;

import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * WCDK 可选响应式事务切面，默认关闭，标准场景使用 Spring Transaction Advisor。
 * 带 {@link Transactional} 的方法必须声明返回 {@link Publisher}，通常为 {@link Mono} 或 {@link Flux}。
 * 同步返回类型会在调用业务方法前被拒绝；事务及方法执行均发生在订阅时。
 * 传播、隔离级别、只读和超时属性传递给 {@link TransactionalOperator}。
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class TransactionalAspect {

    private static final Logger log = LoggerFactory.getLogger(TransactionalAspect.class);

    private final ReactiveTransactionManager transactionManager;

    public TransactionalAspect(ReactiveTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) || " +
            "@within(org.springframework.transaction.annotation.Transactional)")
    public Object handleTransaction(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = resolveMethod(joinPoint);
        Transactional transactional = AnnotationUtils.findAnnotation(method, Transactional.class);
        if (transactional == null) {
            transactional = AnnotationUtils.findAnnotation(method.getDeclaringClass(), Transactional.class);
        }

        if (transactional == null) {
            return joinPoint.proceed();
        }

        if (!Publisher.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException("响应式事务方法必须返回Publisher： "
                    + method.toGenericString());
        }

        boolean readOnly = transactional.readOnly();
        int timeout = transactional.timeout();
        String transactionName = method.getDeclaringClass().getSimpleName() + "." + method.getName();

        if (log.isDebugEnabled()) {
            log.debug("开始{}事务 {}",
                    readOnly ? "只读 " : "", transactionName);
        }

        TransactionalOperator operator = operator(transactional);

        if (Mono.class.isAssignableFrom(method.getReturnType())) {
            return wrapMono(invokeMono(joinPoint, method), operator, timeout);
        }
        return wrapFlux(invokeFlux(joinPoint, method), operator, timeout);
    }

    /** 在订阅时调用被拦截方法。 */
    private Mono<?> invokeMono(ProceedingJoinPoint joinPoint, Method method) {
        return Mono.defer(() -> {
            try {
                return Mono.from(toPublisher(joinPoint.proceed(), method));
            } catch (Throwable error) {
                return Mono.error(error);
            }
        });
    }

    private Flux<?> invokeFlux(ProceedingJoinPoint joinPoint, Method method) {
        return Flux.defer(() -> {
            try {
                return Flux.from(toPublisher(joinPoint.proceed(), method));
            } catch (Throwable error) {
                return Flux.error(error);
            }
        });
    }

    private Publisher<?> toPublisher(Object result, Method method) {
        if (result instanceof Publisher<?> publisher) {
            return publisher;
        }
        throw new IllegalStateException("响应式事务方法必须返回Publisher： " + method.toGenericString());
    }

    private Mono<?> wrapMono(Mono<?> mono, TransactionalOperator operator, int timeout) {
        Mono<?> wrapped = operator.transactional(mono);
        if (hasTimeout(timeout)) {
            wrapped = wrapped.timeout(Duration.ofSeconds(timeout));
        }
        return R2dbcDataSourceContext.pinTransactionDataSource(wrapped);
    }

    /**
     * 包装 Flux 到事务中。
     * <p>
     * 事务在订阅时开始，所有元素完成后提交，异常时回滚。
     */
    private Flux<?> wrapFlux(Flux<?> flux, TransactionalOperator operator, int timeout) {
        Flux<?> wrapped = operator.transactional(flux);
        if (hasTimeout(timeout)) {
            wrapped = wrapped.timeout(Duration.ofSeconds(timeout));
        }
        return R2dbcDataSourceContext.pinTransactionDataSource(wrapped);
    }

    /** 将注解属性传给响应式事务管理器。 */
    private TransactionalOperator operator(Transactional transactional) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(transactional.propagation().value());
        definition.setIsolationLevel(transactional.isolation().value());
        definition.setReadOnly(transactional.readOnly());
        definition.setTimeout(transactional.timeout());
        return TransactionalOperator.create(transactionManager, definition);
    }

    private boolean hasTimeout(int timeout) {
        return timeout > 0 && timeout != org.springframework.transaction.TransactionDefinition.TIMEOUT_DEFAULT;
    }

    private Method resolveMethod(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        try {
            return joinPoint.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return method;
        }
    }
}
