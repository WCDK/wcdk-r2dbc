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

/*** Docker is required: failures are reported, never silently skipped. @author wcdk ***/
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
        // A transaction obtains a PostgreSQL connection before the inner route switches to MySQL.
        assertThatThrownBy(() -> transaction.transactional(R2dbcDataSourceContext.use("mysql",
                        repository.exists(new QueryWrapper<>())))
                .block(DatabaseMatrixSupport.TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事务开始后无法").hasMessageContaining("postgres").hasMessageContaining("mysql");
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
