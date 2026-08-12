package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.core.plan.RepositoryMethodPlan;
import com.wcdk.r2dbc.core.plan.RepositoryPlan;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.lang.reflect.Method;
import java.util.Map;

/***
 * Repository 代理入口。
 *
 * @author wcdk
 **/
final class RepositoryProxyMethodInterceptor implements MethodInterceptor {

    private final RepositoryMethodPlanRegistry planRegistry;
    private final RepositoryInvocationDispatcher dispatcher;

    /***
     * 创建 Repository 代理入口。
     *
     * @param methodPlans 方法计划
     * @param dispatcher  方法分发器
     * @author wcdk
     **/
    RepositoryProxyMethodInterceptor(Map<Method, RepositoryMethodPlan> methodPlans,
                                     RepositoryInvocationDispatcher dispatcher) {
        this.planRegistry = new RepositoryMethodPlanRegistry(methodPlans);
        this.dispatcher = dispatcher;
    }

    /***
     * 按预编译计划分发 Repository 调用，并保持 Reactor Context。
     *
     * @param invocation 方法调用
     * @return 方法调用结果
     * @author wcdk
     **/
    @Override
    public Object invoke(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        RepositoryPlan plan = planRegistry.get(method);
        if (plan == null) {
            throw new IllegalStateException("Repository方法未在启动时编译：" + method);
        }
        if (method.getReturnType() == Mono.class) {
            return Mono.deferContextual(context -> Mono.justOrEmpty(
                    dispatcher.execute(plan, invocation.getArguments(), context, invocation.getThis()))
            ).flatMap(this::toMono);
        }
        if (method.getReturnType() == Flux.class) {
            return Flux.deferContextual(context -> toFlux(
                    dispatcher.execute(plan, invocation.getArguments(), context, invocation.getThis())));
        }
        return dispatcher.execute(plan, invocation.getArguments(), Context.empty(), invocation.getThis());
    }

    /***
     * 将分发结果适配为 Mono。
     *
     * @param result 分发结果
     * @return Mono 结果
     * @author wcdk
     **/
    private Mono<?> toMono(Object result) {
        return result instanceof Mono<?> mono ? mono : Mono.justOrEmpty(result);
    }

    /***
     * 将分发结果适配为 Flux。
     *
     * @param result 分发结果
     * @return Flux 结果
     * @author wcdk
     **/
    private Flux<?> toFlux(Object result) {
        return result instanceof org.reactivestreams.Publisher<?> publisher
                ? Flux.from(publisher)
                : Flux.just(result);
    }

    /***
     * 创建已终止的响应式结果。
     *
     * @param method 目标方法
     * @return Mono 或 Flux
     * @author wcdk
     **/
    static Object terminatedPublisher(Method method) {
        return method.getReturnType() == Flux.class ? Flux.empty() : Mono.empty();
    }
}
