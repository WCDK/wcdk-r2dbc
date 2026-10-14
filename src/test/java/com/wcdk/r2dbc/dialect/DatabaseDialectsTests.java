package com.wcdk.r2dbc.dialect;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseDialectsTests {

    @Test
    void recognizesDmDatabaseMetadata() {
        assertThat(DatabaseDialects.get(connectionFactory("DM Database")))
                .isSameAs(DmDatabaseDialect.INSTANCE);
    }

    @Test
    void recognizesDamengMetadataIgnoringCaseAndWhitespace() {
        assertThat(DatabaseDialects.get(connectionFactory("  DaMeng R2DBC  ")))
                .isSameAs(DmDatabaseDialect.INSTANCE);
    }

    @Test
    void detectsAllFourDatabasesAndUsesTheSameRulesAsDialectSupports() {
        for (String name : new String[]{"MySQL 8.4", " PostgreSQL 16 ", "Oracle Database 21c", "DM Database 60", " DaMeng R2DBC 8 "}) {
            var factory = connectionFactory(name);
            var type = DatabaseType.from(name);
            var dialect = DatabaseDialects.get(factory);
            assertThat(dialect.databaseType()).isEqualTo(type);
            for (var candidate : java.util.List.of(MySqlDatabaseDialect.INSTANCE, PostgreSqlDatabaseDialect.INSTANCE,
                    OracleDatabaseDialect.INSTANCE, DmDatabaseDialect.INSTANCE)) {
                assertThat(candidate.supports(factory)).isEqualTo(candidate.databaseType() == type);
            }
        }
    }

    @Test
    void cachesMetadataParsingPerConnectionFactoryAndRejectsUnknownDatabase() {
        var factory = connectionFactory("Oracle 21c");
        var metadata = factory.getMetadata();
        org.mockito.Mockito.clearInvocations(factory, metadata);
        for (int i=0;i<10;i++) assertThat(DatabaseDialects.get(factory)).isSameAs(OracleDatabaseDialect.INSTANCE);
        org.mockito.Mockito.verify(factory, org.mockito.Mockito.times(1)).getMetadata();
        org.mockito.Mockito.verify(metadata, org.mockito.Mockito.times(1)).getName();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->DatabaseDialects.get(connectionFactory("SQLite")))
                .hasMessageContaining("SQLite");
    }

    private static ConnectionFactory connectionFactory(String name) {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        ConnectionFactoryMetadata metadata = mock(ConnectionFactoryMetadata.class);
        when(factory.getMetadata()).thenReturn(metadata);
        when(metadata.getName()).thenReturn(name);
        return factory;
    }
}