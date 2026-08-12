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
 * 声明式事务切面（响应式适配）。
 * <p>
 * 仅作为 WCDK 特殊扩展拦截带有 {@link Transactional} 注解的方法。
 * 默认不注册，标准场景由 Spring Transaction Advisor 负责。
 * 完全适配响应式架构，支持 Mono、Flux 和非响应式返回类型。
 * <p>
 * 事务传播行为说明：
 * <ul>
 *   <li>REQUIRED（默认）：如果当前存在事务则加入，否则新建事务</li>
 *   <li>REQUIRES_NEW：始终新建事务</li>
 *   <li>SUPPORTS：有事务则加入，无事务则非事务执行</li>
 *   <li>NOT_SUPPORTED：不使用事务（直接执行）</li>
 *   <li>MANDATORY：必须存在事务（直接执行）</li>
 *   <li>NEVER：不能存在事务（直接执行）</li>
 * </ul>
 * <p>
 * 注意：非响应式返回类型会被包装为 Mono 执行，确保事务上下文正确传播。
 *
 * @author WCDK
 * @date 2026/8/6
 * @version 1.0
 **/
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
        if (Flux.class.isAssignableFrom(method.getReturnType())) {
            return wrapFlux(invokeFlux(joinPoint, method), operator, timeout);
        }
        return wrapFlux(invokeFlux(joinPoint, method), operator, timeout);
    }

    /**
     * 包装 Mono 到事务中。
     * <p>
     * 事务在订阅时开始，完成时提交，异常时回滚。
     */
    /**
     * Invoke the intercepted method only when the returned publisher is subscribed.
     */
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
 到事务中。
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

    /**
     * 包装非响应式返回类型到事务中。
     * <p>
     * 将同步方法包装为 Mono 执行，确保事务上下文正确传播。
     * 如果方法执行成功，事务自动提交；如果异常，事务自动回滚。
     *
     * @param result           原始结果
     * @param readOnly         是否只读
     * @param timeout          超时时间（秒）
     * @param transactionName  事务名称
     * @return 包装后的 Mono
     */
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
