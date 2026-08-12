package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryProxyMethodInterceptorTests {

    @Test
    void registryReturnsCompiledPlanWithoutWrapping() throws Exception {
        Method method = ReturnTypes.class.getDeclaredMethod("value");
        RepositoryMethodPlan plan = new RepositoryMethodPlan(
                method, RepositoryMethodPlan.Kind.DERIVED, null, "test.value");
        RepositoryMethodPlanRegistry registry = new RepositoryMethodPlanRegistry(Map.of(method, plan));

        assertThat(registry.get(method)).isSameAs(plan);
    }

    private interface ReturnTypes {
        String value();
    }
}