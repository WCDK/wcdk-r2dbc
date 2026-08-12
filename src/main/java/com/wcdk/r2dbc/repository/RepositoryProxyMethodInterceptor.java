package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import reactor.core.publisher.Mono;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;

/***
 * Repository 代理方法拦截入口。
 * @author wcdk
 **/
final class RepositoryProxyMethodInterceptor implements MethodInterceptor {

    private final Map<Method, RepositoryMethodPlan> methodPlans;
    private final RepositoryInvocationDispatcher dispatcher;

    /***
     * 创建 Repository 代理方法拦截入口。
     *
     * @param methodPlans 方法计划
     * @param dispatcher 方法分发器
     * @author wcdk
     **/
    RepositoryProxyMethodInterceptor(Map<Method, RepositoryMethodPlan> methodPlans,
                                     RepositoryInvocationDispatcher dispatcher) {
        this.methodPlans = Map.copyOf(methodPlans);
        this.dispatcher = dispatcher;
    }

    /***
     * 处理 Repository 方法调用并转交已编译的执行计划。
     *
     * @param invocation 方法调用
     * @return 方法调用结果
     * @author wcdk
     **/
    @Override
    public Object invoke(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        if (isObjectMethod(method)) {
            return invokeObjectMethod(invocation);
        }
        if (method.isDefault()) {
            return invokeDefaultMethod(invocation);
        }

        RepositoryMethodPlan plan = methodPlans.get(method);
        if (plan == null) {
            throw new UnsupportedRepositoryMethodException(method);
        }
        return dispatcher.dispatch(
                plan,
                invocation.getArguments()
        );
    }

    /***
     * 判断是否为 Object 基础方法。
     *
     * @param method 目标方法
     * @return 是否为 Object 基础方法
     * @author wcdk
     **/
    private boolean isObjectMethod(Method method) {
        return method.getDeclaringClass() == Object.class
                || (method.getParameterCount() == 0
                && ("toString".equals(method.getName()) || "hashCode".equals(method.getName())))
                || (method.getParameterCount() == 1 && "equals".equals(method.getName())
                && method.getParameterTypes()[0] == Object.class);
    }

    /***
     * 执行 Object 基础方法，保持代理对象的标准身份语义。
     *
     * @param invocation 方法调用
     * @return Object 方法结果
     * @author wcdk
     **/
    private Object invokeObjectMethod(MethodInvocation invocation) {
        return switch (invocation.getMethod().getName()) {
            case "toString" -> invocation.getThis().getClass().getName()
                    + "@" + Integer.toHexString(System.identityHashCode(invocation.getThis()));
            case "hashCode" -> System.identityHashCode(invocation.getThis());
            case "equals" -> invocation.getArguments() != null
                    && invocation.getArguments().length == 1
                    && invocation.getThis() == invocation.getArguments()[0];
            default -> throw new IllegalStateException("不支持的 Object 方法：" + invocation.getMethod());
        };
    }

    private Object invokeDefaultMethod(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    method.getDeclaringClass(), MethodHandles.lookup());
            MethodHandle handle = lookup.findSpecial(
                    method.getDeclaringClass(), method.getName(),
                    MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                    method.getDeclaringClass())
                    .bindTo(invocation.getThis());
            return handle.invokeWithArguments(invocation.getArguments());
        } catch (Throwable exception) {
            throw new IllegalStateException("执行 Repository 默认方法失败：" + method, exception);
        }
    }
}