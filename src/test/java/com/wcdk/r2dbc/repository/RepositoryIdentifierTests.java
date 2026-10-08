package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.dialect.*;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import static org.assertj.core.api.Assertions.assertThat;

/*** 数据库保留字、映射列及引用转义回归。 @author wcdk ***/
class RepositoryIdentifierTests {
    @Test
    void mysqlQuotesReservedTableAndMappedColumnWithBackticks() {
        var metadata = new RepositoryMetadata(Setting.class, new WcdkR2dbcProperties(), MySqlDatabaseDialect.INSTANCE);
        assertThat(metadata.tableName()).isEqualTo("`order`");
        assertThat(metadata.columnByName("settingKey").name()).isEqualTo("`key`");
        assertThat(metadata.columnByName("key")).isSameAs(metadata.columnByName("settingKey"));
        assertThat(metadata.columnByName("`key`")).isSameAs(metadata.columnByName("settingKey"));
    }

    @Test
    void ansiDialectsKeepDoubleQuotes() {
        for (DatabaseDialect dialect : java.util.List.of(PostgreSqlDatabaseDialect.INSTANCE, OracleDatabaseDialect.INSTANCE, DmDatabaseDialect.INSTANCE)) {
            var metadata = new RepositoryMetadata(Setting.class, new WcdkR2dbcProperties(), dialect);
            assertThat(metadata.tableName()).isEqualTo("\"order\"");
            assertThat(metadata.columnByName("key").name()).isEqualTo("\"key\"");
        }
    }

    @Test
    void disabledQuotingPreservesRawMappedNames() {
        var properties = new WcdkR2dbcProperties();
        properties.setQuoteIdentifier(false);
        var metadata = new RepositoryMetadata(Setting.class, properties, MySqlDatabaseDialect.INSTANCE);
        assertThat(metadata.tableName()).isEqualTo("order");
        assertThat(metadata.columnByName("key").name()).isEqualTo("key");
    }

    @Test
    void embeddedIdentifierQuoteIsEscapedByDialect() {
        var metadata = new RepositoryMetadata(Escaped.class, new WcdkR2dbcProperties(), MySqlDatabaseDialect.INSTANCE);
        assertThat(metadata.tableName()).isEqualTo("`qa``table`");
        assertThat(metadata.columnByName("qa`key").name()).isEqualTo("`qa``key`");
    }

    @Test
    void legacyConstructorKeepsAnsiCompatibility() {
        var metadata = new RepositoryMetadata(Setting.class, new WcdkR2dbcProperties());
        assertThat(metadata.columnByName("key").name()).isEqualTo("\"key\"");
    }

    @Table("order")
    static class Setting {
        @Id Long id;
        @Column("key") String settingKey;
    }

    @Table("qa`table")
    static class Escaped {
        @Column("qa`key") String settingKey;
    }
}
