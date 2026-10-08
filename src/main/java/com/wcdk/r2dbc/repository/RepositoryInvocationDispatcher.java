package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.Map;

/***
 * Repository 方法执行分发器。
 * @author wcdk
 */
final class RepositoryInvocationDispatcher {
    private final List<RepositoryMethodExecutor> executors;
    private final com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory routing;
    private final Map<String, Map<java.lang.reflect.Method, RepositoryMethodPlan>> routedPlans;

    RepositoryInvocationDispatcher(List<RepositoryMethodExecutor> executors) {
        this.executors = List.copyOf(executors);
        this.routing = null;
        this.routedPlans = Map.of();
    }

    /*** 绑定各数据源的独立不可变计划，在订阅时按 Reactor Context 选择。 @author wcdk ***/
    RepositoryInvocationDispatcher(com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory routing,
                                   Map<String, Map<java.lang.reflect.Method, RepositoryMethodPlan>> routedPlans) {
        this.executors = List.of();
        this.routing = routing;
        this.routedPlans = Map.copyOf(routedPlans);
    }

    /***
     * 根据编译计划分发 Repository 调用，并在此处统一处理响应式返回值。
     *
     * @param plan Repository 方法计划
     * @param invocation 方法调用
     * @return 方法调用结果
     * @author wcdk
     **/
    /***
     * 为编译计划绑定唯一执行器。
     *
     * @param plans 编译计划
     * @return 已绑定执行器的不可变计划
     * @author wcdk
     **/
    Map<java.lang.reflect.Method, RepositoryMethodPlan> bindPlans(
            Map<java.lang.reflect.Method, RepositoryMethodPlan> plans) {
        return plans.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> entry.getValue().withExecutor(findExecutor(entry.getValue()))));
    }
    Object dispatch(RepositoryMethodPlan plan, Object[] args) {
        if (plan.method().getReturnType() == Mono.class) {
            return Mono.deferContextual(context -> Mono.justOrEmpty(
                    execute(plan, args, context, null)))
                    .flatMap(this::toMono);
        }
        if (plan.method().getReturnType() == Flux.class) {
            return Flux.deferContextual(context -> toFlux(
                    execute(plan, args, context, null)));
        }
        return execute(plan, args, Context.empty(), null);
    }

    private Object execute(RepositoryMethodPlan plan, Object[] args,
                           ContextView context, Object proxy) {
        if (routing != null) {
            String key = com.wcdk.r2dbc.datasource.R2dbcDataSourceContext.get(context);
            routing.getConnectionFactory(key); // 与连接路由一致地校验数据源键。
            key = key == null || key.isBlank() ? routing.getPrimary() : key;
            plan = routedPlans.get(key).get(plan.method());
        }
        return (plan.executor() == null ? findExecutor(plan) : plan.executor())
                .execute(plan, args, context, proxy);
    }

    private RepositoryMethodExecutor findExecutor(RepositoryMethodPlan plan) {
        return executors.stream()
                .filter(executor -> executor.supports(plan))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "没有执行器支持该 Repository 方法：" + plan.method().toGenericString()));
    }

    private Mono<?> toMono(Object result) {
        return result instanceof Mono<?> mono ? mono : Mono.justOrEmpty(result);
    }

    private Flux<?> toFlux(Object result) {
        return result instanceof org.reactivestreams.Publisher<?> publisher
                ? Flux.from(publisher)
                : result == null ? Flux.empty() : Flux.just(result);
    }
}
