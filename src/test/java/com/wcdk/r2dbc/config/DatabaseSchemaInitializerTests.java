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

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
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


    @Test
    void classifiesOnlyExactDirectorySegmentsAndExplicitPrefixes() {
        for (String path : List.of("admin.sql", "add_column.sql", "user_id_map.sql", "admin/notes_mysql.sql"))
            assertThat(DatabaseSchemaInitializer.scriptDatabaseType(resource(path, "SELECT 1;"))).isNull();
        for (String type : List.of("dm", "mysql", "oracle", "postgresql", "postgres", "dameng")) {
            String expected = type.equals("postgres") ? "postgresql" : type.equals("dameng") ? "dm" : type;
            assertThat(DatabaseSchemaInitializer.scriptDatabaseType(resource("init/" + type + "/schema.sql", "SELECT 1;"))).isEqualTo(expected);
            assertThat(DatabaseSchemaInitializer.scriptDatabaseType(resource(type + "-schema.sql", "SELECT 1;"))).isEqualTo(expected);
            assertThat(DatabaseSchemaInitializer.scriptDatabaseType(resource(type + "_schema.sql", "SELECT 1;"))).isEqualTo(expected);
        }
    }

    @Test
    void selectsMatchingDatabaseScriptsOrGenericFallbackAndExcludesOtherDirectories() throws Exception {
        Fixture fixture = fixture("always");
        when(fixture.resolver.getResources(anyString())).thenReturn(new Resource[]{
                resource("admin.sql", "SELECT 'admin';"), resource("add_column.sql", "SELECT 'column';"),
                resource("user_id_map.sql", "SELECT 'map';"), resource("mysql/schema.sql", "SELECT 'mysql';"),
                resource("dm/schema.sql", "SELECT 'dm';")});
        fixture.initializer.run();
        verify(fixture.connection).createStatement("SELECT 'admin'");
        verify(fixture.connection).createStatement("SELECT 'column'");
        verify(fixture.connection).createStatement("SELECT 'map'");
        verify(fixture.connection, never()).createStatement("SELECT 'mysql'");
        verify(fixture.connection, never()).createStatement("SELECT 'dm'");
        clearInvocations(fixture.connection);
        when(fixture.resolver.getResources(anyString())).thenReturn(new Resource[]{
                resource("admin.sql", "SELECT 'admin';"), resource("postgresql/schema.sql", "SELECT 'postgres';")});
        fixture.initializer.run();
        verify(fixture.connection).createStatement("SELECT 'postgres'");
        verify(fixture.connection, never()).createStatement("SELECT 'admin'");
    }

    @Test
    void ignoresOnlyWhitelistedDatabaseErrorsOnBothExecutionPaths(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        for (boolean transaction : List.of(false, true)) {
            Fixture fixture = fixture("always");
            fixture.properties.getDatabaseInitializer().setExecuteInTransaction(transaction);
            fixture.properties.getDatabaseInitializer().setIgnoreErrors(true);
            when(fixture.resolver.getResources(anyString())).thenReturn(new Resource[]{resource("schema.sql", "CREATE TABLE sample(id INT);")});
            var allowed = new io.r2dbc.spi.R2dbcBadGrammarException("duplicate table", "42P07", 0);
            statementFailure(fixture, transaction, new IllegalStateException("translated", allowed));
            fixture.initializer.run();
            for (Throwable rejected : List.of(new io.r2dbc.spi.R2dbcBadGrammarException("already exists", "42601", 0),
                    new io.r2dbc.spi.R2dbcBadGrammarException("unknown driver", null, 0), new IllegalStateException("already exists"))) {
                statementFailure(fixture, transaction, rejected);
                assertThatThrownBy(fixture.initializer::run).isSameAs(rejected);
            }
            fixture.properties.getDatabaseInitializer().setIgnoreErrors(false);
            statementFailure(fixture, transaction, allowed);
            assertThatThrownBy(fixture.initializer::run).isSameAs(allowed);
        }
        assertThat(output.getAll()).contains("database=postgresql", "script=schema.sql", "SQLSTATE=42P07", "code=0", "reason=duplicate table");
    }

    private void statementFailure(Fixture fixture, boolean transaction, Throwable error) {
        if (transaction) doReturn(Flux.error(error)).when(fixture.statement).execute();
        else {
            var spec = mock(DatabaseClient.GenericExecuteSpec.class);
            when(fixture.client.sql(anyString())).thenReturn(spec);
            when(spec.then()).thenReturn(Mono.error(error));
        }
    }

    private Resource resource(String path, String sql) {
        return new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), path) {
            @Override public String getFilename() { return path.substring(path.lastIndexOf('/') + 1); }
            @Override public String getDescription() { return path; }
        };
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
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseSchemaInitializer initializer = new DatabaseSchemaInitializer(
                client, factory, resolver, properties, "");
        return new Fixture(initializer, connection, statement, resolver, properties, client);
    }

    private record Fixture(DatabaseSchemaInitializer initializer, Connection connection, Statement statement,
                           ResourcePatternResolver resolver, WcdkR2dbcProperties properties, DatabaseClient client) {
    }
}
