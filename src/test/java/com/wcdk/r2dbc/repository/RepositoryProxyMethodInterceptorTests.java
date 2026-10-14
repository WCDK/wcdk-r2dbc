package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RepositoryProxyMethodInterceptorTests {

    interface ObjectMethods { String toString(); int hashCode(); boolean equals(Object other); }

    @Test
    void redeclaredObjectMethodsUseProxyIdentity() {
        var factory = new ProxyFactory(); factory.setInterfaces(ObjectMethods.class);
        factory.addAdvice(new RepositoryProxyMethodInterceptor(Map.of(), mock(RepositoryInvocationDispatcher.class)));
        var proxy = (ObjectMethods) factory.getProxy();
        assertThat(proxy.toString()).contains("@" + Integer.toHexString(System.identityHashCode(proxy)));
        assertThat(proxy.hashCode()).isEqualTo(System.identityHashCode(proxy));
        assertThat(proxy.equals(proxy)).isTrue(); assertThat(proxy.equals(new Object())).isFalse();
    }

    @Test
    void inheritedDefaultMethodReentersDispatcherOnTargetlessProxy() throws Exception {
        Method method = ReturnTypes.class.getDeclaredMethod("value");
        var plan = new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.DERIVED, null, "test.value");
        var dispatcher = mock(RepositoryInvocationDispatcher.class);
        when(dispatcher.dispatch(eq(plan), any())).thenReturn("value");
        var factory = new ProxyFactory();
        factory.setInterfaces(ChildReturnTypes.class);
        factory.addAdvice(new RepositoryProxyMethodInterceptor(Map.of(method, plan), dispatcher));
        var repository = (ChildReturnTypes) factory.getProxy();

        assertThat(repository.label("prefix:")).isEqualTo("prefix:value");
        verify(dispatcher).dispatch(eq(plan), any());
    }

    private interface ChildReturnTypes extends ReturnTypes {
    }

    private interface ReturnTypes {
        String value();

        default String label(String prefix) {
            return prefix + value();
        }
    }
}
