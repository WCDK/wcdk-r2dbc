package com.wcdk.r2dbc.core;
import com.wcdk.r2dbc.core.plan.RepositoryMethodPlan;

import com.wcdk.r2dbc.BaseRepository;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.core.xml.RepositoryXmlRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepositoryMethodPlanCompilerTests {

    @Test
    void compilesStableImmutableDispatchPlansAtStartup() throws Exception {
        WcdkR2dbcProperties properties = new WcdkR2dbcProperties();
        RepositoryXmlRegistry xmlRegistry = mock(RepositoryXmlRegistry.class);
        when(xmlRegistry.find(any(), any())).thenReturn(Optional.empty());
        RepositoryMetadata metadata = new RepositoryMetadata(User.class, properties);

        Map<Method, RepositoryMethodPlan> plans = new RepositoryMethodPlanCompiler(
                UserRepository.class, metadata, xmlRegistry, properties).compile();

        Method derived = UserRepository.class.getMethod("findByName", String.class);
        Method unsupported = UserRepository.class.getMethod("custom", String.class);
        Method insert = BaseRepository.class.getMethod("insert", Object.class);
        Method toString = Object.class.getMethod("toString");
        assertThat(plans.get(derived).kind()).isEqualTo(RepositoryMethodPlan.Kind.DERIVED);
        assertThat(plans.get(derived).statementId())
                .isEqualTo(UserRepository.class.getName() + ".findByName");
        assertThat(plans.get(unsupported).kind()).isEqualTo(RepositoryMethodPlan.Kind.UNSUPPORTED);
        assertThat(plans.get(insert).kind()).isEqualTo(RepositoryMethodPlan.Kind.CRUD);
        assertThat(plans.get(toString).kind()).isEqualTo(RepositoryMethodPlan.Kind.OBJECT);
        assertThatThrownByMutation(plans, derived);
        assertThat(plans.get(derived).statementDefinition().kind())
                .isEqualTo(RepositoryMethodPlan.Kind.DERIVED);
        assertThat(plans.get(derived).parameterPlan().parameters()).hasSize(1);
        assertThat(plans.get(derived).parameterPlan().parameters().get(0).type())
                .isEqualTo(String.class);
        assertThat(plans.get(derived).resultMappingPlan().reactiveElementType())
                .isEqualTo(User.class);
        assertThat(plans.get(derived).resultMappingPlan().entityType())
                .isEqualTo(User.class);
        assertThat(plans.get(derived).sqlPlan().deferred()).isTrue();
    }

    private void assertThatThrownByMutation(Map<Method, RepositoryMethodPlan> plans, Method method) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> plans.remove(method))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    interface UserRepository extends BaseRepository<User> {
        Mono<User> findByName(String name);
        Mono<User> custom(String value);
    }

    static class User {
        private Long id;
        private String name;
        private Integer delFlg;
    }
}
