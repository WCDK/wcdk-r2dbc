package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.R2dbcRepositoryOperations;
import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.dialect.MySqlDatabaseDialect;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptor;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.id.SnowflakeIdGenerator;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigInteger;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/*** 从仓储到驱动语句的插入主键回填回归测试。 @author wcdk ***/
class CrudInsertGeneratedIdTests {
    @Test
    void returnsRecordWithSnowflakeIdAndPreservesEveryOtherComponent() throws Exception {
        Fixture fixture = new Fixture(RecordUser.class, true, 42L);
        Object marker = new Object();
        RecordUser original = new RecordUser("测试", null, marker);
        Mono<Object> insert = fixture.insert(original);
        verifyNoInteractions(fixture.connection);
        doAnswer(invocation -> {
            var context = (com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext) invocation.getArgument(0);
            RecordUser effective = (RecordUser) context.getArguments()[0];
            assertThat(effective.userId()).isPositive();
            return null;
        }).when(fixture.interceptor).beforeExecute(any());
        StepVerifier.create(insert).assertNext(value -> {
            RecordUser saved = (RecordUser) value;
            assertThat(saved).isNotSameAs(original);
            assertThat(saved.userId()).isPositive();
            assertThat(saved.name()).isEqualTo(original.name());
            assertThat(saved.marker()).isSameAs(marker);
            verify(fixture.statement).bind(eq(1), argThat(parameter -> parameter instanceof Parameter bound
                    && saved.userId().equals(bound.getValue())));
        }).verifyComplete();
        assertThat(original.userId()).isNull();
        verify(fixture.statement, never()).returnGeneratedValues(any(String[].class));
        verify(fixture.interceptor).afterExecute(any());
    }

    @Test
    void returnsRecordWithDatabaseGeneratedIdAndPreservesTransientComponent() throws Exception {
        Fixture fixture = new Fixture(RecordUser.class, false, BigInteger.valueOf(42));
        Object marker = new Object();
        RecordUser original = new RecordUser("测试", null, marker);
        doAnswer(invocation -> {
            var context = (com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext) invocation.getArgument(0);
            assertThat(((RecordUser) context.getResult()).userId()).isEqualTo(42L);
            assertThat(context.getArguments()[0]).isSameAs(context.getResult());
            return null;
        }).when(fixture.interceptor).afterExecute(any());
        StepVerifier.create(fixture.insert(original)).assertNext(value -> {
            assertThat(value).isEqualTo(new RecordUser("测试", 42L, marker)).isNotSameAs(original);
        }).verifyComplete();
        assertThat(original.userId()).isNull();
        verify(fixture.statement).returnGeneratedValues("user_key");
        verify(fixture.interceptor).afterExecute(any());
    }

    @Test
    void preservesAssignedRecordIdAndInstance() throws Exception {
        Fixture fixture = new Fixture(RecordUser.class, true, 42L);
        RecordUser original = new RecordUser("测试", 7L, null);
        StepVerifier.create(fixture.insert(original))
                .assertNext(value -> assertThat(value).isSameAs(original)).verifyComplete();
        verify(fixture.statement, never()).returnGeneratedValues(any(String[].class));
    }

    @Test
    void supportsStringSnowflakeIdInRecord() throws Exception {
        Fixture fixture = new Fixture(StringRecordUser.class, true, 42L);
        StringRecordUser original = new StringRecordUser(null, "测试");
        StepVerifier.create(fixture.insert(original)).assertNext(value -> {
            StringRecordUser saved = (StringRecordUser) value;
            assertThat(Long.parseLong(saved.id())).isPositive();
            assertThat(saved.name()).isEqualTo(original.name());
        }).verifyComplete();
        assertThat(original.id()).isNull();
        verify(fixture.statement, never()).returnGeneratedValues(any(String[].class));
    }

    @Test
    void supportsPrimitiveSnowflakeIdAndIndependentRecordSubscriptions() throws Exception {
        Fixture fixture = new Fixture(PrimitiveRecordUser.class, true, 42L);
        PrimitiveRecordUser original = new PrimitiveRecordUser(0, "测试");
        Mono<Object> insert = fixture.insert(original);
        StepVerifier.create(Flux.concat(insert, insert).collectList()).assertNext(values -> {
            PrimitiveRecordUser first = (PrimitiveRecordUser) values.get(0);
            PrimitiveRecordUser second = (PrimitiveRecordUser) values.get(1);
            assertThat(first.id()).isPositive();
            assertThat(second.id()).isPositive().isNotEqualTo(first.id());
            assertThat(first.name()).isEqualTo(original.name());
            assertThat(second.name()).isEqualTo(original.name());
        }).verifyComplete();
        assertThat(original.id()).isZero();
        verify(fixture.statement, times(2)).execute();
    }

    @Test
    void fillsMappedIdAfterExecutionAndRunsLifecycleOnce() throws Exception {
        Fixture fixture = new Fixture(User.class, false, BigInteger.valueOf(42));
        User user = new User();
        Mono<?> insert = fixture.insert(user);
        assertThat(user.userId).isNull();
        verifyNoInteractions(fixture.connection);

        doAnswer(invocation -> {
            assertThat(user.userId).isEqualTo(42L);
            return null;
        }).when(fixture.interceptor).afterExecute(any());
        StepVerifier.create(insert).assertNext(value -> assertThat(value).isSameAs(user)).verifyComplete();

        verify(fixture.statement).returnGeneratedValues("user_key");
        verify(fixture.connection).createStatement("INSERT INTO `user` (`name`) VALUES ($1)");
        verify(fixture.statement).bind(eq(0), argThat(value -> value instanceof Parameter parameter
                && "测试".equals(parameter.getValue())));
        verify(fixture.statement).execute();
        verify(fixture.connection).close();
        verify(fixture.interceptor).beforeExecute(any());
        verify(fixture.interceptor).afterExecute(any());
    }

    @Test
    void preservesAssignedId() throws Exception {
        Fixture fixture = new Fixture(User.class, false, 42L);
        User user = new User();
        user.userId = 7L;
        StepVerifier.create(fixture.insert(user)).expectNext(user).verifyComplete();
        assertThat(user.userId).isEqualTo(7L);
        verify(fixture.statement, never()).returnGeneratedValues(any(String[].class));
        verify(fixture.statement).bind(eq(0), argThat(value -> value instanceof Parameter parameter
                && Long.valueOf(7).equals(parameter.getValue())));
    }

    @Test
    void preservesSnowflakeId() throws Exception {
        Fixture fixture = new Fixture(User.class, true, 42L);
        User user = new User();
        StepVerifier.create(fixture.insert(user)).expectNext(user).verifyComplete();
        assertThat(user.userId).isPositive();
        verify(fixture.statement, never()).returnGeneratedValues(any(String[].class));
        verify(fixture.statement).bind(eq(0), argThat(value -> value instanceof Parameter parameter
                && user.userId.equals(parameter.getValue())));
    }

    @Test
    void generatesIdForPrimitiveDefaultZero() throws Exception {
        Fixture fixture = new Fixture(PrimitiveUser.class, false, BigInteger.valueOf(42));
        PrimitiveUser user = new PrimitiveUser();
        StepVerifier.create(fixture.insert(user)).expectNext(user).verifyComplete();
        assertThat(user.id).isEqualTo(42);
        verify(fixture.statement).returnGeneratedValues("id");
        verify(fixture.connection).createStatement("INSERT INTO `primitive_user` (`name`) VALUES ($1)");
    }

    @Test
    void convertsGeneratedValueToStringId() throws Exception {
        Fixture fixture = new Fixture(StringUser.class, false, 42L);
        StringUser user = new StringUser();
        StepVerifier.create(fixture.insert(user)).expectNext(user).verifyComplete();
        assertThat(user.id).isEqualTo("42");
    }

    @Test
    void insertsEntityWithoutIdNormally() throws Exception {
        Fixture fixture = new Fixture(NoIdUser.class, false, 42L);
        NoIdUser user = new NoIdUser();
        StepVerifier.create(fixture.insert(user)).expectNext(user).verifyComplete();
        verify(fixture.statement, never()).returnGeneratedValues(any(String[].class));
        verify(fixture.statement).execute();
    }

    @Test
    void reportsMissingGeneratedKeyInsteadOfReturningUnfilledEntity() throws Exception {
        Fixture fixture = new Fixture(User.class, false, null);
        User user = new User();
        StepVerifier.create(fixture.insert(user))
                .expectErrorMatches(error -> error.getMessage().contains("数据库未返回生成的主键"))
                .verify();
        assertThat(user.userId).isNull();
        verify(fixture.statement).execute();
        verify(fixture.connection).close();
    }

    @Test
    void propagatesInsertFailureWithoutFillingId() throws Exception {
        Fixture fixture = new Fixture(User.class, false, 42L);
        IllegalStateException failure = new IllegalStateException("插入失败");
        doReturn(Mono.error(failure)).when(fixture.statement).execute();
        User user = new User();
        StepVerifier.create(fixture.insert(user)).expectErrorMatches(error -> error == failure).verify();
        assertThat(user.userId).isNull();
        verify(fixture.connection).close();
    }

    /*** 使用真实 DatabaseClient 和仓储适配器，仅替换数据库驱动边界。 @author wcdk ***/
    private static class Fixture {
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class, RETURNS_SELF);
        final SqlLifecycleInterceptor interceptor = mock(SqlLifecycleInterceptor.class);
        final CrudRepositoryExecutor executor;

        Fixture(Class<?> type, boolean snowflake, Object generatedId) {
            ConnectionFactory factory = mock(ConnectionFactory.class);
            doReturn(Mono.just(connection)).when(factory).create();
            when(connection.createStatement(anyString())).thenReturn(statement);
            doReturn(Mono.empty()).when(connection).close();
            Result result = mock(Result.class);
            doReturn(Mono.just(result)).when(statement).execute();
            doReturn(Mono.just(1L)).when(result).getRowsUpdated();
            Row row = mock(Row.class);
            when(row.get(0)).thenReturn(generatedId);
            when(result.map(any(BiFunction.class))).thenAnswer(invocation -> {
                BiFunction<Row, RowMetadata, Object> mapper = invocation.getArgument(0);
                return generatedId == null ? Flux.empty() : Flux.just(mapper.apply(row, mock(RowMetadata.class)));
            });
            DatabaseClient client = DatabaseClient.builder().connectionFactory(factory)
                    .bindMarkers(BindMarkersFactory.indexed("$", 1)).build();
            WcdkR2dbcProperties properties = new WcdkR2dbcProperties();
            properties.setSnowflakeId(snowflake);
            properties.setSqlLogEnabled(false);
            R2dbcUtil util = new R2dbcUtil(client, null, null, properties, new WcdkSpringR2dbcProperties(),
                    null, new SqlLifecycleInterceptorChain(List.of(interceptor), List.of()));
            executor = new CrudRepositoryExecutor(properties,
                    new RepositoryMetadata(type, properties, MySqlDatabaseDialect.INSTANCE),
                    BaseRepository.class, null, MySqlDatabaseDialect.INSTANCE, new SnowflakeIdGenerator(1),
                    new SqlExecutionEngine(new R2dbcRepositoryOperations(util)), new RepositoryParameterBinder());
        }

        Mono<Object> insert(Object entity) throws Exception {
            var plan = new RepositoryMethodPlan(BaseRepository.class.getMethod("insert", Object.class),
                    RepositoryMethodPlan.Kind.CRUD, null, "insert");
            return ((Mono<?>) executor.executeCrudPlan(plan, new Object[]{entity}, Context.empty())).cast(Object.class);
        }
    }

    /*** 自定义映射主键实体。 @author wcdk ***/
    static class User {
        @Id @Column("user_key") Long userId;
        String name = "测试";
    }

    /*** 基本类型主键实体。 @author wcdk ***/
    static class PrimitiveUser {
        @Id int id;
        String name = "测试";
    }

    /*** 字符串主键实体。 @author wcdk ***/
    static class StringUser {
        @Id String id;
        String name = "测试";
    }

    /*** 无主键实体。 @author wcdk ***/
    static class NoIdUser {
        String name = "测试";
    }

    /*** 主键非首个组件且带有非持久化组件的不可变实体。 @author wcdk ***/
    private record RecordUser(String name, @Id @Column("user_key") Long userId, @Transient Object marker) {
    }

    /*** 字符串主键不可变实体。 @author wcdk ***/
    private record StringRecordUser(@Id String id, String name) {
    }

    /*** 基本类型主键不可变实体。 @author wcdk ***/
    private record PrimitiveRecordUser(@Id long id, String name) {
    }
}
