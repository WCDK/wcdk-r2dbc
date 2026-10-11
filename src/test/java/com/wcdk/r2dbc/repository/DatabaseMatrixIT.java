package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import com.wcdk.r2dbc.query.QueryWrapper;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 必须提供 Docker 环境；测试失败会明确报告，不会静默跳过。
 * @author wcdk
 */
class DatabaseMatrixIT {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @BeforeAll
    static void start() {
        POSTGRES.start();
        MYSQL.start();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
        POSTGRES.stop();
    }

    @Test
    void postgresRepositoryMatrix() {
        DatabaseMatrixSupport.verify(postgres());
    }

    @Test
    void mysqlRepositoryMatrix() {
        DatabaseMatrixSupport.verify(mysql());
    }

    @Test
    void actualTransactionRejectsSwitchToAnotherDatabase() {
        var routing = new DynamicRoutingConnectionFactory("postgres", Map.of("postgres", postgres(), "mysql", mysql()));
        var transaction = TransactionalOperator.create(new R2dbcTransactionManager(routing));
        var repository = DatabaseMatrixSupport.repository(routing);
        // 事务先获取 PostgreSQL 连接，随后内部路由尝试切换到 MySQL。
        assertThatThrownBy(() -> transaction.transactional(R2dbcDataSourceContext.use("mysql",
                        repository.exists(new QueryWrapper<>())))
                .block(DatabaseMatrixSupport.TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事务开始后无法").hasMessageContaining("postgres").hasMessageContaining("mysql");
    }


    @Test
    void schemaInitializerRecoversDuplicateObjectsOnRealPostgresAndMysql() throws Exception {
        for (ConnectionFactory factory : java.util.List.of(postgres(), mysql())) {
            var client = org.springframework.r2dbc.core.DatabaseClient.create(factory);
            String table = "wcdk_init_recovery";
            client.sql("DROP TABLE IF EXISTS " + table).then().block(DatabaseMatrixSupport.TIMEOUT);
            var properties = new com.wcdk.r2dbc.config.WcdkR2dbcProperties();
            var config = properties.getDatabaseInitializer();
            config.setEnabled(true); config.setIgnoreErrors(true); config.setExecuteInTransaction(false);
            var resolver = org.mockito.Mockito.mock(org.springframework.core.io.support.ResourcePatternResolver.class);
            var resource = new org.springframework.core.io.ByteArrayResource(("CREATE TABLE " + table
                    + " (value INT); INSERT INTO " + table + " (value) VALUES (1);").getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                @Override public String getFilename() { return "schema.sql"; }
            };
            org.mockito.Mockito.when(resolver.getResources(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new org.springframework.core.io.Resource[]{resource});
            var initializer = new com.wcdk.r2dbc.config.DatabaseSchemaInitializer(client, factory, resolver, properties, "");
            try {
                initializer.run(); initializer.run();
                org.assertj.core.api.Assertions.assertThat(client.sql("SELECT COUNT(*) FROM " + table)
                        .map((row, metadata) -> ((Number) row.get(0)).longValue()).one().block(DatabaseMatrixSupport.TIMEOUT)).isEqualTo(2L);
                config.setIgnoreErrors(false);
                assertThatThrownBy(initializer::run).isInstanceOf(RuntimeException.class);
                config.setIgnoreErrors(true);
                var invalid = new org.springframework.core.io.ByteArrayResource("INVALID SQL;".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    @Override public String getFilename() { return "schema.sql"; }
                };
                org.mockito.Mockito.when(resolver.getResources(org.mockito.ArgumentMatchers.anyString()))
                        .thenReturn(new org.springframework.core.io.Resource[]{invalid});
                assertThatThrownBy(initializer::run).isInstanceOf(RuntimeException.class);
            } finally {
                client.sql("DROP TABLE " + table).then().block(DatabaseMatrixSupport.TIMEOUT);
            }
        }
    }

    private static ConnectionFactory postgres() {
        return factory("postgresql", POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static ConnectionFactory mysql() {
        return factory("mysql", MYSQL.getHost(), MYSQL.getMappedPort(3306), MYSQL.getDatabaseName(),
                MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static ConnectionFactory factory(String driver, String host, int port, String database, String user, String password) {
        return DatabaseMatrixSupport.connectionFactory(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, driver).option(ConnectionFactoryOptions.HOST, host)
                .option(ConnectionFactoryOptions.PORT, port).option(ConnectionFactoryOptions.DATABASE, database)
                .option(ConnectionFactoryOptions.USER, user).option(ConnectionFactoryOptions.PASSWORD, password).build());
    }
}
