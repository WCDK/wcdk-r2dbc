package com.wcdk.r2dbc.config;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DatabaseSchemaInitializerTests {

    @Test
    void splitsQuotedDollarQuotedDelimitedAndPlsqlScripts() {
        String script = """
                -- regular statement with a quoted semicolon
                INSERT INTO sample(value) VALUES ('a;b');
                CREATE FUNCTION f() RETURNS void AS $body$
                BEGIN
                  PERFORM 'x;y';
                END;
                $body$ LANGUAGE plpgsql;
                DELIMITER $$
                CREATE PROCEDURE p() BEGIN SELECT 'm;n'; END$$
                DELIMITER ;
                DECLARE
                  value NUMBER;
                BEGIN
                  value := 1;
                END;
                /
                """;

        List<String> statements = DatabaseSchemaInitializer.splitSqlScript(script);

        assertThat(statements).hasSize(4);
        assertThat(statements.get(0)).contains("'a;b'");
        assertThat(statements.get(1)).contains("$body$", "PERFORM 'x;y'");
        assertThat(statements.get(2)).contains("CREATE PROCEDURE", "'m;n'");
        assertThat(statements.get(3)).startsWith("DECLARE").contains("value := 1;");
    }

    @Test
    void rejectsInvalidModeBeforeConnecting() {
        Fixture fixture = fixture("sometimes");

        assertThatThrownBy(fixture.initializer::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database-initializer.mode");
        verifyNoInteractions(fixture.connection);
    }

    @Test
    void transactionOptionBeginsCommitsAndClosesOneConnection() throws Exception {
        Fixture fixture = fixture("always");
        Resource sql = new ByteArrayResource(
                "CREATE TABLE sample(id INT);".getBytes(StandardCharsets.UTF_8), "schema.sql") {
            @Override
            public String getFilename() {
                return "schema.sql";
            }
        };
        when(fixture.resolver.getResources(anyString())).thenReturn(new Resource[]{sql});

        fixture.initializer.run();

        verify(fixture.connection).beginTransaction();
        verify(fixture.connection).commitTransaction();
        verify(fixture.connection, never()).rollbackTransaction();
        verify(fixture.connection).close();
    }

    @Test
    void transactionOptionRollsBackAndClosesOnStatementFailure() throws Exception {
        Fixture fixture = fixture("always");
        Resource sql = new ByteArrayResource("BROKEN SQL;".getBytes(StandardCharsets.UTF_8), "schema.sql") {
            @Override
            public String getFilename() {
                return "schema.sql";
            }
        };
        when(fixture.resolver.getResources(anyString())).thenReturn(new Resource[]{sql});
        doReturn(Flux.error(new IllegalStateException("database failure")))
                .when(fixture.statement).execute();

        assertThatThrownBy(fixture.initializer::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database failure");

        verify(fixture.connection).beginTransaction();
        verify(fixture.connection, never()).commitTransaction();
        verify(fixture.connection).rollbackTransaction();
        verify(fixture.connection).close();
    }

    private Fixture fixture(String mode) {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        Result result = mock(Result.class);
        when(connection.beginTransaction()).thenReturn(Mono.empty());
        when(connection.commitTransaction()).thenReturn(Mono.empty());
        when(connection.rollbackTransaction()).thenReturn(Mono.empty());
        when(connection.close()).thenReturn(Mono.empty());
        when(connection.createStatement(anyString())).thenReturn(statement);
        doReturn(Flux.just(result)).when(statement).execute();
        when(result.getRowsUpdated()).thenReturn(Mono.just(0L));

        ConnectionFactory factory = mock(ConnectionFactory.class);
        ConnectionFactoryMetadata metadata = mock(ConnectionFactoryMetadata.class);
        when(metadata.getName()).thenReturn("PostgreSQL");
        when(factory.getMetadata()).thenReturn(metadata);
        doReturn(Mono.just(connection)).when(factory).create();

        WcdkR2dbcProperties properties = new WcdkR2dbcProperties();
        properties.getDatabaseInitializer().setEnabled(true);
        properties.getDatabaseInitializer().setMode(mode);
        properties.getDatabaseInitializer().setDatabaseType("postgresql");
        properties.getDatabaseInitializer().setIgnoreErrors(false);
        properties.getDatabaseInitializer().setExecuteInTransaction(true);

        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        DatabaseSchemaInitializer initializer = new DatabaseSchemaInitializer(
                mock(DatabaseClient.class), factory, resolver, properties, "");
        return new Fixture(initializer, connection, statement, resolver);
    }

    private record Fixture(DatabaseSchemaInitializer initializer, Connection connection, Statement statement,
                           ResourcePatternResolver resolver) {
    }
}
