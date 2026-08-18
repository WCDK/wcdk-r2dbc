package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySpecExecutorTests {

    @Test
    void selectByIdIsNotHandledAsQuerySpec() throws Exception {
        Method method = BaseRepository.class.getMethod("selectById", Object.class);
        RepositoryMethodPlan plan = new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.CRUD, null,
                "selectById");

        assertThat(new QuerySpecExecutor(null).supports(plan)).isFalse();
    }

    @Test
    void selectByIdIsHandledByCrudExecutor() throws Exception {
        Method method = BaseRepository.class.getMethod("selectById", Object.class);
        RepositoryMethodPlan plan = new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.CRUD, null,
                "selectById");

        CrudRepositoryExecutor executor = Mockito.mock(CrudRepositoryExecutor.class,
                Mockito.CALLS_REAL_METHODS);

        assertThat(executor.supports(plan)).isTrue();
    }
}
