package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.R2dbcRepositoryOperations;
import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptor;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.repository.query.Param;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/*** 通过真实仓储代理、XML 编译及 DatabaseClient 验证 null 更新，只替换驱动边界。 @author wcdk ***/
class CrudUpdateNullTests {

    @ParameterizedTest
    @ValueSource(strings = {"updateById", "updateByIdIgnoringNulls"})
    void ignoresNullsAndPreservesMappedIdAndLogicDeleteFilter(String method) throws Exception {
        var fixture = new Fixture<>(Users.class, "");
        var user = new User();
        user.age = 30;
        StepVerifier.create(fixture.update(method, user)).expectNext(1L).verifyComplete();

        assertThat(fixture.sql()).contains("SET \"age\" = $1")
                .contains("WHERE \"user_key\" = $2 AND \"del_flg\" = $3")
                .doesNotContain("\"display_name\" =", "\"marker\"", "SET \"user_key\"");
        fixture.bound(0, 30, Integer.class);
        fixture.bound(1, 7L, Long.class);
        fixture.bound(2, 0, Integer.class);
        assertThat(fixture.context().getMethod().getName()).isEqualTo(method);
        verify(fixture.connection).close();
    }

    @Test
    void clearsSingleColumnUsingTypedNull() throws Exception {
        var fixture = new Fixture<>(SingleColumnUsers.class, "");
        StepVerifier.create(fixture.repository.updateByIdIncludingNulls(new SingleColumnUser()))
                .expectNext(1L).verifyComplete();
        assertThat(fixture.sql()).isEqualTo("UPDATE \"single_column_user\" SET \"name\" = $1 WHERE \"id\" = $2");
        fixture.bound(0, null, String.class);
        fixture.bound(1, 7L, Long.class);
    }

    @Test
    void mixesNullAndNonNullFieldsAndExcludesTransient() throws Exception {
        var fixture = new Fixture<>(Users.class, "");
        var user = new User();
        user.age = 30;
        user.delFlg = 0;
        StepVerifier.create(fixture.repository.updateByIdIncludingNulls(user)).expectNext(1L).verifyComplete();

        assertThat(fixture.sql()).contains("SET \"display_name\" = $1, \"age\" = $2, \"del_flg\" = $3")
                .contains("WHERE \"user_key\" = $4 AND \"del_flg\" = $5")
                .doesNotContain("\"marker\"");
        fixture.bound(0, null, String.class);
        fixture.bound(1, 30, Integer.class);
        fixture.bound(2, 0, Integer.class);
        fixture.bound(3, 7L, Long.class);
        fixture.bound(4, 0, Integer.class);
        assertThat(fixture.context().getMethod().getName()).isEqualTo("updateByIdIncludingNulls");
    }

    @Test
    void includingNullsUpdatesAllNullPersistentFields() throws Exception {
        var fixture = new Fixture<>(Users.class, "");
        StepVerifier.create(fixture.repository.updateByIdIncludingNulls(new User())).expectNext(1L).verifyComplete();
        fixture.bound(0, null, String.class);
        fixture.bound(1, null, Integer.class);
        fixture.bound(2, null, Integer.class);
        assertThat(fixture.sql()).contains("SET \"display_name\" =", "\"age\" =", "\"del_flg\" =");
    }

    @ParameterizedTest
    @ValueSource(strings = {"updateById", "updateByIdIgnoringNulls"})
    void allNullPatchReturnsZeroWithoutAcquiringConnection(String method) throws Exception {
        var fixture = new Fixture<>(Users.class, "");
        StepVerifier.create(fixture.update(method, new User())).expectNext(0L).verifyComplete();
        verify(fixture.factory, never()).create();
        verifyNoInteractions(fixture.statement);
        assertThat(fixture.context().getSql()).isNullOrEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"updateById", "updateByIdIgnoringNulls", "updateByIdIncludingNulls"})
    void idOnlyEntityNeverGeneratesEmptySet(String method) throws Exception {
        var fixture = new Fixture<>(IdOnlyUsers.class, "");
        StepVerifier.create(fixture.update(method, new IdOnlyUser())).expectNext(0L).verifyComplete();
        verify(fixture.factory, never()).create();
        verifyNoInteractions(fixture.statement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"updateById", "updateByIdIgnoringNulls", "updateByIdIncludingNulls"})
    void returnsActualZeroRowsInsteadOfAssumingSuccess(String method) throws Exception {
        var fixture = new Fixture<>(Users.class, "");
        fixture.rows = 0;
        var user = new User();
        user.name = "new";
        StepVerifier.create(fixture.update(method, user)).expectNext(0L).verifyComplete();
        verify(fixture.statement).execute();
    }

    @Test
    void reSubscriptionRebuildsAnInitiallyEmptyPatch() throws Exception {
        var fixture = new Fixture<>(Users.class, "");
        var user = new User();
        Mono<Long> update = fixture.repository.updateByIdIgnoringNulls(user);
        StepVerifier.create(update).expectNext(0L).verifyComplete();
        user.name = "new";
        StepVerifier.create(update).expectNext(1L).verifyComplete();
        fixture.bound(0, "new", String.class);
    }

    @Test
    void xmlBindsNullAndReportsOptimisticVersionMismatchAsZero() throws Exception {
        var fixture = new Fixture<>(Users.class, """
                <update id="updateNameIfVersion">
                  UPDATE users SET display_name = #{name}, version = version + 1
                  WHERE user_key = #{id} AND version = #{version}
                </update>
                """);
        fixture.rows = 0;
        StepVerifier.create(fixture.repository.updateNameIfVersion(7L, null, 2)).expectNext(0L).verifyComplete();
        assertThat(fixture.sql()).contains("display_name = $1", "version = version + 1",
                "user_key = $2 AND version = $3").doesNotContain("age =");
        fixture.bound(0, null, String.class);
        fixture.bound(1, 7L, Long.class);
        fixture.bound(2, 2, Integer.class);
    }

    @Test
    void xmlCanClearColumnWithLiteralNull() throws Exception {
        var fixture = new Fixture<>(Users.class, """
                <update id="clearName">UPDATE users SET display_name = NULL WHERE user_key = #{id}</update>
                """);
        StepVerifier.create(fixture.repository.clearName(7L)).expectNext(1L).verifyComplete();
        assertThat(fixture.sql()).contains("SET display_name = NULL WHERE user_key = $1");
        fixture.bound(0, 7L, Long.class);
    }

    @Test
    void databaseErrorsArePropagated() throws Exception {
        var fixture = new Fixture<>(SingleColumnUsers.class, "");
        var error = new IllegalStateException("NOT NULL constraint");
        doReturn(Mono.error(error)).when(fixture.statement).execute();
        StepVerifier.create(fixture.repository.updateByIdIncludingNulls(new SingleColumnUser()))
                .expectErrorMatches(actual -> actual == error).verify();
        verify(fixture.connection).close();
    }

    private static class Fixture<R> {
        final ConnectionFactory factory = mock(ConnectionFactory.class);
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class, RETURNS_SELF);
        final SqlLifecycleInterceptor interceptor = mock(SqlLifecycleInterceptor.class);
        final R repository;
        final Class<R> repositoryType;
        long rows = 1;

        Fixture(Class<R> repositoryType, String xmlStatements) throws Exception {
            this.repositoryType = repositoryType;
            ConnectionFactoryMetadata metadata = mock(ConnectionFactoryMetadata.class);
            when(metadata.getName()).thenReturn("PostgreSQL");
            when(factory.getMetadata()).thenReturn(metadata);
            doReturn(Mono.just(connection)).when(factory).create();
            when(connection.createStatement(anyString())).thenReturn(statement);
            doReturn(Mono.empty()).when(connection).close();
            Result result = mock(Result.class);
            doReturn(Mono.just(result)).when(statement).execute();
            when(result.getRowsUpdated()).thenAnswer(invocation -> Mono.just(rows));
            DatabaseClient client = DatabaseClient.builder().connectionFactory(factory)
                    .bindMarkers(BindMarkersFactory.indexed("$", 1)).build();
            var properties = new WcdkR2dbcProperties();
            properties.setSnowflakeId(false);
            properties.setSqlLogEnabled(false);
            properties.setMapperLocations("memory:test");
            if (repositoryType == Users.class) {
                if (!xmlStatements.contains("id=\"clearName\"")) {
                    xmlStatements += "<update id=\"clearName\">UPDATE users SET display_name = NULL WHERE user_key = #{id}</update>";
                }
                if (!xmlStatements.contains("id=\"updateNameIfVersion\"")) {
                    xmlStatements += "<update id=\"updateNameIfVersion\">UPDATE users SET display_name = #{name} WHERE user_key = #{id} AND version = #{version}</update>";
                }
            }
            String xml = "<repository namespace=\"" + repositoryType.getName() + "\">"
                    + xmlStatements + "</repository>";
            ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
            when(resolver.getResources("memory:test")).thenReturn(new Resource[]{
                    new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8), "update-tests.xml")});
            var registry = new RepositoryXmlRegistry(resolver, properties);
            var util = new R2dbcUtil(client, null, null, properties, new WcdkSpringR2dbcProperties(),
                    null, new SqlLifecycleInterceptorChain(List.of(interceptor), List.of()));
            repository = repositoryType.cast(new RepositoryProxyFactory(
                    new R2dbcRepositoryOperations(util), properties, registry).create(repositoryType));
        }

        @SuppressWarnings("unchecked")
        Mono<Long> update(String method, Object entity) throws Exception {
            return (Mono<Long>) BaseRepository.class.getMethod(method, Object.class).invoke(repository, entity);
        }

        String sql() {
            var capture = ArgumentCaptor.forClass(String.class);
            verify(connection).createStatement(capture.capture());
            return capture.getValue();
        }

        void bound(int index, Object value, Class<?> javaType) {
            var capture = ArgumentCaptor.forClass(Object.class);
            verify(statement).bind(eq(index), capture.capture());
            // DatabaseClient wraps both ordinary values and typed NULLs in R2DBC Parameters.
            assertThat(capture.getValue()).isInstanceOf(Parameter.class);
            Parameter parameter = (Parameter) capture.getValue();
            assertThat(parameter.getValue()).isEqualTo(value);
            assertThat(parameter.getType().getJavaType()).isEqualTo(javaType);
        }

        SqlExecutionContext context() {
            var capture = ArgumentCaptor.forClass(SqlExecutionContext.class);
            verify(interceptor).beforeCompile(capture.capture());
            return capture.getValue();
        }
    }

    interface Users extends BaseRepository<User> {
        Mono<Long> updateNameIfVersion(@Param("id") Long id, @Param("name") String name, @Param("version") Integer version);
        Mono<Long> clearName(@Param("id") Long id);
    }

    interface SingleColumnUsers extends BaseRepository<SingleColumnUser> {}
    interface IdOnlyUsers extends BaseRepository<IdOnlyUser> {}

    static class User {
        @Id @Column("user_key") Long userId = 7L;
        @Column("display_name") String name;
        Integer age;
        Integer delFlg;
        @Transient String marker = "ignored";
    }

    static class SingleColumnUser {
        @Id Long id = 7L;
        String name;
    }

    static class IdOnlyUser {
        @Id Long id = 7L;
        @Transient String marker;
    }
}
