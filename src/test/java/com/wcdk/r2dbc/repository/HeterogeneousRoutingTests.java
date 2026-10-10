package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.R2dbcRepositoryOperations;
import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.config.WcdkR2dbcAutoConfiguration;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import com.wcdk.r2dbc.dialect.DatabaseDialects;
import com.wcdk.r2dbc.dialect.DatabaseType;
import com.wcdk.r2dbc.execution.SqlParameter;
import com.wcdk.r2dbc.query.QueryWrapper;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/*** 异构路由下真实仓储代理、参数绑定和事务连接的回归测试。 @author wcdk ***/
class HeterogeneousRoutingTests {
    @Test
    void existenceQueriesUseOneRowAndNumericResultsAcrossRoutes() {
        Fixture fixture = new Fixture();
        for (String key : fixture.nodes.keySet()) {
            StepVerifier.create(R2dbcDataSourceContext.use(key, fixture.repository.exists(new QueryWrapper<>())))
                    .expectNext(false).verifyComplete();
            StepVerifier.create(R2dbcDataSourceContext.use(key, fixture.repository.exists(
                            new QueryWrapper<Setting>().eq("settingKey", "missing"))))
                    .expectNext(false).verifyComplete();
            StepVerifier.create(R2dbcDataSourceContext.use(key, fixture.repository.existsBySettingKey("missing")))
                    .expectNext(false).verifyComplete();
            var sql = fixture.nodes.get(key).sql;
            assertThat(sql).hasSize(3).allSatisfy(query -> {
                assertThat(query).contains("SELECT 1 FROM").doesNotContain("COUNT(", "THEN TRUE", "ELSE FALSE");
                assertThat(query).contains(key.equals("mysql") || key.equals("postgres")
                        ? "LIMIT 1" : "FETCH");
            });
            assertThat(sql.get(2)).contains("THEN 1 ELSE 0 END");
        }
    }
    @Test
    void samePublisherUsesTargetQuotesMarkersAndPaginationAcrossConcurrentSubscriptions() {
        Fixture fixture = new Fixture();
        var publisher = fixture.repository.selectList(new QueryWrapper<Setting>()
                .eq("settingKey", "测试").orderByAsc("settingKey").limit(2));
        StepVerifier.create(Flux.merge(fixture.nodes.keySet().stream()
                .map(key -> R2dbcDataSourceContext.use(key, publisher).collectList()
                        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())).toList()))
                .expectNextCount(4).verifyComplete();
        assertThat(fixture.nodes.get("mysql").sql).singleElement().asString()
                .contains("FROM `order`", "`key` = ?", "ORDER BY `key` ASC", "LIMIT 2");
        assertThat(fixture.nodes.get("postgres").sql).singleElement().asString()
                .contains("FROM \"order\"", "\"key\" = $1", "ORDER BY \"key\" ASC", "LIMIT 2");
        assertThat(fixture.nodes.get("dm").sql).singleElement().asString()
                .contains("FROM \"order\"", "\"key\" = ?", "FETCH FIRST 2 ROWS ONLY");
        assertThat(fixture.nodes.get("oracle").sql).singleElement().asString()
                .contains("FROM \"order\"", "\"key\" = :", "FETCH FIRST 2 ROWS ONLY");
    }

    @Test
    void derivedQueryPlansUseTargetIdentifiersAndDefaultRouteStillUsesPrimary() {
        Fixture fixture = new Fixture();
        Mono<Long> count = fixture.repository.countBySettingKey("测试");
        StepVerifier.create(count).expectNext(0L).verifyComplete();
        StepVerifier.create(R2dbcDataSourceContext.use("postgres", count)).expectNext(0L).verifyComplete();
        assertThat(fixture.nodes.get("mysql").sql).singleElement().asString()
                .contains("FROM `order`", "`key` = ?");
        assertThat(fixture.nodes.get("postgres").sql).singleElement().asString()
                .contains("FROM \"order\"", "\"key\" = $1");
    }

    @Test
    void insertUsesTargetIdentifiersAndTemporalConverter() {
        Fixture fixture = new Fixture();
        Instant instant = Instant.parse("2026-10-08T00:00:00Z");
        Setting setting = new Setting(7L, "测试", instant);
        Mono<Setting> insert = fixture.repository.insert(setting);
        for (String key : fixture.nodes.keySet()) {
            StepVerifier.create(R2dbcDataSourceContext.use(key, insert)).expectNext(setting).verifyComplete();
            Node node = fixture.nodes.get(key);
            assertThat(node.sql.getFirst()).contains(key.equals("mysql") ? "INSERT INTO `order`" : "INSERT INTO \"order\"");
            assertThat(node.parameters.stream().map(Parameter::getValue).toList())
                    .contains(key.equals("dm") ? Date.from(instant) : instant);
        }
        assertThat(DatabaseDialects.get(fixture.nodes.get("dm").factory).databaseType()).isEqualTo(DatabaseType.DM);
    }

    @Test
    void pageCountAndRecordsBothUseTargetDialect() {
        Fixture fixture = new Fixture();
        StepVerifier.create(R2dbcDataSourceContext.use("oracle", fixture.repository.selectPage(
                        PageRequest.of(1, 5, Sort.by(Sort.Order.desc("settingKey"))),
                        new QueryWrapper<Setting>().eq("settingKey", "测试"))))
                .assertNext(page -> assertThat(page.getTotalElements()).isZero()).verifyComplete();
        assertThat(fixture.nodes.get("oracle").sql).hasSize(2)
                .allSatisfy(sql -> assertThat(sql).contains("FROM \"order\"", "\"key\" = :"));
        assertThat(fixture.nodes.get("oracle").sql.getFirst()).doesNotContain("ORDER BY");
        assertThat(fixture.nodes.get("oracle").sql.get(1))
                .contains("ORDER BY \"key\" DESC OFFSET 5 ROWS FETCH NEXT 5 ROWS ONLY");
    }

    @Test
    void pageableSortCombinesWithWrapperAcrossRoutes() {
        Fixture fixture = new Fixture();
        var wrapper = new QueryWrapper<Setting>().orderByAsc("key").orderByAsc("id");
        var pageable = PageRequest.of(0, 5, Sort.by(Sort.Order.desc("settingKey")));
        var publisher = fixture.repository.selectPage(pageable, wrapper);
        for (String key : fixture.nodes.keySet()) {
            StepVerifier.create(R2dbcDataSourceContext.use(key, publisher))
                    .assertNext(page -> {
                        assertThat(page.getSort()).isEqualTo(pageable.getSort());
                        assertThat(page.getTotalElements()).isZero();
                    }).verifyComplete();
            List<String> sql = fixture.nodes.get(key).sql;
            assertThat(sql).hasSize(2);
            assertThat(sql.getFirst()).doesNotContain("ORDER BY");
            assertThat(sql.get(1)).contains(key.equals("mysql")
                    ? "ORDER BY `key` DESC, `id` ASC" : "ORDER BY \"key\" DESC, \"id\" ASC");
        }
        assertThat(wrapper.orderByList()).containsExactly(
                new QueryWrapper.OrderBy("key", true), new QueryWrapper.OrderBy("id", true));
    }

    @Test
    void defaultPageOverloadAppliesUnpagedSortWithoutLimit() {
        Fixture fixture = new Fixture();
        StepVerifier.create(fixture.repository.selectPage(Pageable.unpaged(Sort.by("settingKey"))))
                .assertNext(page -> assertThat(page.getSort()).isEqualTo(Sort.by("settingKey")))
                .verifyComplete();
        assertThat(fixture.nodes.get("mysql").sql.get(1))
                .contains("ORDER BY `key` ASC").doesNotContain("LIMIT", "OFFSET");
    }

    @Test
    void invalidPageSortFailsBeforeCountOrRecordStatements() {
        Fixture fixture = new Fixture();
        StepVerifier.create(fixture.repository.selectPage(PageRequest.of(0, 5, Sort.by("missing"))))
                .expectErrorMatches(error -> error.getMessage().contains("实体字段不存在"))
                .verify();
        fixture.nodes.values().forEach(node -> assertThat(node.sql).isEmpty());
    }

    @Test
    void generatedKeysUseTargetDialectAndStillReturnNewRecord() {
        Fixture fixture = new Fixture();
        Setting original = new Setting(null, "测试", Instant.parse("2026-10-08T00:00:00Z"));
        Mono<Setting> insert = fixture.repository.insert(original);
        for (String key : fixture.nodes.keySet()) {
            StepVerifier.create(R2dbcDataSourceContext.use(key, insert))
                    .assertNext(saved -> assertThat(saved).isEqualTo(new Setting(42L, original.settingKey(), original.createdAt()))
                            .isNotSameAs(original)).verifyComplete();
            assertThat(fixture.nodes.get(key).sql.getFirst())
                    .contains(key.equals("mysql") ? "(`key`, `created_at`)" : "(\"key\", \"created_at\")");
        }
        assertThat(original.id()).isNull();
    }

    @Test
    void autoConfiguredBinderRoutesTypedNullAndNativeTemporalParameters() {
        Fixture fixture = new Fixture();
        var binder = new WcdkR2dbcAutoConfiguration().parameterBinder(fixture.routing);
        Instant instant = Instant.parse("2026-10-08T00:00:00Z");
        for (String key : List.of("dm", "postgres")) {
            StepVerifier.create(R2dbcDataSourceContext.use(key, Mono.deferContextual(context -> binder.bind(
                            fixture.client, "UPDATE sample SET created_at = :value, optional_at = :optional",
                            Map.of("value", instant, "optional", SqlParameter.nullOf(Instant.class)), context)
                    .fetch().rowsUpdated()))).expectNext(1L).verifyComplete();
            List<Parameter> parameters = fixture.nodes.get(key).parameters;
            assertThat(parameters.get(0).getValue()).isEqualTo(key.equals("dm") ? Date.from(instant) : instant);
            assertThat(parameters.get(1).getType().getJavaType()).isEqualTo(key.equals("dm") ? Date.class : Instant.class);
        }
    }

    @Test
    void targetClientsReuseConnectionBoundToRoutingFactoryInTransaction() {
        Fixture fixture = new Fixture();
        var operator = TransactionalOperator.create(new R2dbcTransactionManager(fixture.routing));
        var updates = fixture.util.update("UPDATE sample SET value = :value", Map.of("value", 1))
                .then(fixture.util.update("UPDATE sample SET value = :value", Map.of("value", 2)));
        StepVerifier.create(R2dbcDataSourceContext.use("postgres", operator.transactional(updates)))
                .expectNext(1L).verifyComplete();
        Node postgres = fixture.nodes.get("postgres");
        verify(postgres.factory).create();
        verify(postgres.connection).commitTransaction();
        verify(postgres.connection).close();
        assertThat(postgres.sql).hasSize(2).allSatisfy(sql -> assertThat(sql).contains("$1"));
        verify(fixture.nodes.get("mysql").factory, never()).create();
    }

    @Test
    void unknownRouteFailsBeforeAnyStatementExecutes() {
        Fixture fixture = new Fixture();
        StepVerifier.create(R2dbcDataSourceContext.use("missing", fixture.repository.selectCount(new QueryWrapper<>())))
                .expectErrorMatches(error -> error.getMessage().contains("R2DBC数据源不存在"))
                .verify();
        fixture.nodes.values().forEach(node -> assertThat(node.sql).isEmpty());
    }

    /*** 共享一个路由工厂和代理，四个驱动边界分别记录执行结果。 @author wcdk ***/
    private static class Fixture {
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final DynamicRoutingConnectionFactory routing;
        final DatabaseClient client;
        final R2dbcUtil util;
        final SettingRepository repository;

        Fixture() {
            nodes.put("mysql", new Node("MySQL"));
            nodes.put("postgres", new Node("PostgreSQL"));
            nodes.put("oracle", new Node("Oracle"));
            nodes.put("dm", new Node("DM"));
            Map<String, ConnectionFactory> factories = new LinkedHashMap<>();
            nodes.forEach((key, node) -> factories.put(key, node.factory));
            routing = new DynamicRoutingConnectionFactory("mysql", factories);
            client = DatabaseClient.create(routing);
            WcdkR2dbcProperties properties = new WcdkR2dbcProperties();
            properties.setSqlLogEnabled(false);
            util = new R2dbcUtil(client, null, null, properties);
            repository = (SettingRepository) new RepositoryProxyFactory(new R2dbcRepositoryOperations(util),
                    properties, mock(RepositoryXmlRegistry.class)).create(SettingRepository.class);
        }
    }

    /*** 最小驱动边界，用真实 DatabaseClient 验证 SQL 和参数。 @author wcdk ***/
    private static class Node {
        final ConnectionFactory factory = mock(ConnectionFactory.class);
        final Connection connection = mock(Connection.class);
        final List<String> sql = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Parameter> parameters = new java.util.concurrent.CopyOnWriteArrayList<>();

        Node(String name) {
            when(factory.getMetadata()).thenReturn(() -> name);
            doReturn(Mono.just(connection)).when(factory).create();
            doReturn(Mono.empty()).when(connection).close();
            when(connection.isAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolationLevel()).thenReturn(IsolationLevel.READ_COMMITTED);
            doReturn(Mono.empty()).when(connection).beginTransaction(any());
            doReturn(Mono.empty()).when(connection).beginTransaction();
            doReturn(Mono.empty()).when(connection).commitTransaction();
            doReturn(Mono.empty()).when(connection).rollbackTransaction();
            doReturn(Mono.empty()).when(connection).setAutoCommit(anyBoolean());
            when(connection.createStatement(anyString())).thenAnswer(invocation -> {
                String statementSql = invocation.getArgument(0);
                sql.add(statementSql);
                Statement statement = mock(Statement.class, RETURNS_SELF);
                doAnswer(binding -> {
                    parameters.add(binding.getArgument(1));
                    return statement;
                }).when(statement).bind(anyInt(), any());
                doAnswer(binding -> {
                    parameters.add(binding.getArgument(1));
                    return statement;
                }).when(statement).bind(anyString(), any());
                Result result = mock(Result.class);
                doReturn(Mono.just(1L)).when(result).getRowsUpdated();
                when(result.map(any(BiFunction.class))).thenAnswer(mapping -> {
                    if (!statementSql.startsWith("INSERT")) return Flux.empty();
                    Row row = mock(Row.class);
                    when(row.get(0)).thenReturn(java.math.BigInteger.valueOf(42));
                    BiFunction<Row, RowMetadata, Object> mapper = mapping.getArgument(0);
                    return Flux.just(mapper.apply(row, mock(RowMetadata.class)));
                });
                doReturn(Mono.just(result)).when(statement).execute();
                return statement;
            });
        }
    }

    /*** 包含派生查询的路由仓储。 @author wcdk ***/
    interface SettingRepository extends BaseRepository<Setting> {
        Mono<Long> countBySettingKey(String value);
        Mono<Boolean> existsBySettingKey(String value);
    }

    /*** 保留字表列及时间参数实体。 @author wcdk ***/
    @Table("order")
    private record Setting(@Id Long id, @Column("key") String settingKey, Instant createdAt) {
    }
}
