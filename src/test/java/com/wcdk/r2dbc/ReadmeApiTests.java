package com.wcdk.r2dbc;

import com.wcdk.r2dbc.query.QueryWrapper;
import com.wcdk.r2dbc.query.LambdaQueryWrapper;
import com.wcdk.r2dbc.query.LambdaUpdateWrapper;
import com.wcdk.r2dbc.query.LambdaDeleteWrapper;
import com.wcdk.r2dbc.repository.BaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Compiled equivalents of the README migration examples. */
@SuppressWarnings("removal")
class ReadmeApiTests {
    @Table("sys_user")
    public record User(@Id Long id, @Column("user_name") String userName, String email, Integer status) { }

    @com.wcdk.r2dbc.annotation.Repository
    public interface UserRepository extends BaseRepository<User> {
        Flux<User> findByStatus(Integer status);
        Mono<Long> updateStatusById(Integer status, Long id);
        Mono<Long> deleteByStatus(Integer status);
    }

    @com.wcdk.r2dbc.Repository
    public interface LegacyRepository extends BaseRepository<User> { }

    @Test
    void migrationExamplesCompileAndUseSupportedRepositoryEntries() {
        UserRepository repository = mock(UserRepository.class);
        User user = new User(1L, "张三", "u@example.com", 1);
        when(repository.selectList(any())).thenReturn(Flux.just(user));
        when(repository.updateStatusById(1, 1L)).thenReturn(Mono.just(1L));
        when(repository.deleteByStatus(0)).thenReturn(Mono.just(2L));
        QueryWrapper<User> wrapper = new QueryWrapper<User>().eq("status", 1).orderByDesc("id");
        Flux<User> users = repository.selectList(wrapper);
        Mono<Long> updated = repository.updateStatusById(1, 1L);
        Mono<Long> deleted = repository.deleteByStatus(0);
        StepVerifier.create(users).expectNext(user).verifyComplete();
        StepVerifier.create(updated).expectNext(1L).verifyComplete();
        StepVerifier.create(deleted).expectNext(2L).verifyComplete();
        verify(repository).selectList(wrapper);
    }

    @Test
    void legacyClassesRemainAvailableWithExplicitDeprecationAndReplacementAnnotations() {
        for (Class<?> type : new Class<?>[]{Repository.class, LambdaQueryWrapper.class,
                LambdaUpdateWrapper.class, LambdaDeleteWrapper.class}) {
            var deprecated = type.getAnnotation(Deprecated.class);
            assertThat(deprecated).isNotNull();
            assertThat(deprecated.since()).isEqualTo("3.5.16");
            assertThat(deprecated.forRemoval()).isTrue();
        }
        assertThat(LegacyRepository.class.isAnnotationPresent(Repository.class)).isTrue();
        assertThat(UserRepository.class.isAnnotationPresent(com.wcdk.r2dbc.annotation.Repository.class)).isTrue();
    }
}
