package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import reactor.core.publisher.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 真实编译 README 派生方法及迁移接口，避免仅靠接口 Mock 掩盖不支持的方法名。
 * @author wcdk
 */
class ReadmeRepositoryContractsTests {
    @Test void documentedDerivedMethodsCompileToExecutablePlans() throws Exception {
        var properties = new WcdkR2dbcProperties();
        var metadata = new RepositoryMetadata(User.class, properties);
        var resources = mock(ResourcePatternResolver.class);
        when(resources.getResources(anyString())).thenReturn(new Resource[0]);
        var registry = new RepositoryXmlRegistry(resources, properties);
        var plans = new RepositoryMethodPlanCompiler(Users.class, metadata, registry, properties).compile();
        for (var method : Users.class.getDeclaredMethods()) {
            assertThat(plans.get(method).kind()).as(method.toGenericString()).isEqualTo(RepositoryMethodPlan.Kind.DERIVED);
        }
        var resolver = new CustomMethodResolver(metadata, 1, 0);
        var method = Users.class.getMethod("updateStatusById", Integer.class, Long.class);
        var parsed = resolver.resolve(method, new Object[]{1, 7L});
        assertThat(parsed.sql()).contains("UPDATE", "SET", "WHERE");
        assertThat(parsed.parameters()).containsValue(1).containsValue(7L);
    }

    @Table("sys_user")
    record User(@Id Long id, @Column("user_name") String userName, String email, Integer status) {}
    interface Users extends BaseRepository<User> {
        Flux<User> findByStatus(Integer status);
        Flux<User> findByStatusAndEmail(Integer status, String email);
        Flux<User> findByStatusOrEmail(Integer status, String email);
        Mono<Long> countByStatus(Integer status);
        Mono<Boolean> existsByEmail(String email);
        Mono<Long> deleteByStatus(Integer status);
        Mono<Integer> updateUserNameById(String userName, Long id);
        Mono<Long> updateStatusById(Integer status, Long id);
    }
}
