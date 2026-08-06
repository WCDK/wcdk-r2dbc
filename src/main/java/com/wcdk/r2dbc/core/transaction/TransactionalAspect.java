package com.wcdk.r2dbc.core.transaction;

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
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 声明式事务切面（响应式适配）。
 * <p>
 * 拦截带有 {@link Transactional} 注解的方法，自动开启事务。
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

    private final TransactionalOperator transactionalOperator;

    private final TransactionTemplate transactionTemplate;

    public TransactionalAspect(TransactionalOperator transactionalOperator, TransactionTemplate transactionTemplate) {
        this.transactionalOperator = transactionalOperator;
        this.transactionTemplate = transactionTemplate;
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) || " +
            "@within(org.springframework.transaction.annotation.Transactional)")
    public Object handleTransaction(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = resolveMethod(joinPoint);
        Transactional transactional = AnnotationUtils.findAnnotation(method, Transactional.class);

        if (transactional == null) {
            return joinPoint.proceed();
        }

        if (isUnsupportedPropagation(transactional.propagation())) {
            log.debug("Propagation {} not supported in R2DBC, proceeding without transaction",
                    transactional.propagation());
            return joinPoint.proceed();
        }

        boolean readOnly = transactional.readOnly();
        int timeout = transactional.timeout();
        String transactionName = method.getDeclaringClass().getSimpleName() + "." + method.getName();

        if (log.isDebugEnabled()) {
            log.debug("Starting {}transaction for {}",
                    readOnly ? "read-only " : "", transactionName);
        }

        Object result = joinPoint.proceed();

        if (result instanceof Mono<?> mono) {
            return wrapMono(mono, readOnly, timeout, transactionName);
        }
        if (result instanceof Flux<?> flux) {
            return wrapFlux(flux, readOnly, timeout, transactionName);
        }
        if (result instanceof Publisher<?> publisher) {
            return wrapFlux(Flux.from(publisher), readOnly, timeout, transactionName);
        }

        return wrapBlocking(result, readOnly, timeout, transactionName);
    }

    /**
     * 包装 Mono 到事务中。
     * <p>
     * 事务在订阅时开始，完成时提交，异常时回滚。
     */
    private Mono<?> wrapMono(Mono<?> mono, boolean readOnly, int timeout, String transactionName) {
        Mono<?> wrapped;

        if (readOnly) {
            wrapped = transactionTemplate.wrapReadOnly(mono);
        } else {
            wrapped = transactionalOperator.transactional(mono);
        }

        if (hasTimeout(timeout)) {
            wrapped = wrapped.timeout(Duration.ofSeconds(timeout));
        }

        return wrapped;
    }

    /**
     * 包装 Flux 到事务中。
     * <p>
     * 事务在订阅时开始，所有元素完成后提交，异常时回滚。
     */
    private Flux<?> wrapFlux(Flux<?> flux, boolean readOnly, int timeout, String transactionName) {
        Flux<?> wrapped = transactionalOperator.transactional(flux);

        if (hasTimeout(timeout)) {
            wrapped = wrapped.timeout(Duration.ofSeconds(timeout));
        }

        return wrapped;
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
    private Mono<?> wrapBlocking(Object result, boolean readOnly, int timeout, String transactionName) {
        if (result == null) {
            return Mono.empty();
        }

        Mono<?> mono = Mono.just(result);

        if (readOnly) {
            mono = transactionTemplate.wrapReadOnly(mono);
        } else {
            mono = transactionalOperator.transactional(mono);
        }

        if (hasTimeout(timeout)) {
            mono = mono.timeout(Duration.ofSeconds(timeout));
        }

        return mono;
    }

    private boolean hasTimeout(int timeout) {
        return timeout > 0 && timeout != org.springframework.transaction.TransactionDefinition.TIMEOUT_DEFAULT;
    }

    private boolean isUnsupportedPropagation(org.springframework.transaction.annotation.Propagation propagation) {
        return switch (propagation) {
            case NOT_SUPPORTED, MANDATORY, NEVER -> true;
            default -> false;
        };
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
