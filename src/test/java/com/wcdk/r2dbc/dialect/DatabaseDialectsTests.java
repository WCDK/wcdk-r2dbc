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

    private static ConnectionFactory connectionFactory(String name) {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        ConnectionFactoryMetadata metadata = mock(ConnectionFactoryMetadata.class);
        when(factory.getMetadata()).thenReturn(metadata);
        when(metadata.getName()).thenReturn(name);
        return factory;
    }
}