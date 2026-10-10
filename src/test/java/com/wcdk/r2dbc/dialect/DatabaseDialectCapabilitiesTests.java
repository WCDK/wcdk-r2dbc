package com.wcdk.r2dbc.dialect;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDialectCapabilitiesTests {
    @Test
    void generatedKeysAndSqlCapabilitiesHaveExplicitContracts() {
        var postgres = PostgreSqlDatabaseDialect.INSTANCE;
        var mysql = MySqlDatabaseDialect.INSTANCE;
        var oracle = OracleDatabaseDialect.INSTANCE;
        var dm = DmDatabaseDialect.INSTANCE;
        assertThat(postgres.generatedKeyStrategy()).isEqualTo(GeneratedKeyStrategy.RETURNING);
        assertThat(mysql.generatedKeyStrategy()).isEqualTo(GeneratedKeyStrategy.LAST_INSERT_ID);
        assertThat(oracle.generatedKeyStrategy()).isEqualTo(GeneratedKeyStrategy.RETURNING);
        assertThat(dm.generatedKeyStrategy()).isEqualTo(GeneratedKeyStrategy.RETURNING);
        assertThat(postgres.supportsReturning()).isTrue();
        assertThat(postgres.renderGeneratedKey("\"id\"")).isEqualTo(" RETURNING \"id\"");
        assertThat(mysql.supportsReturning()).isFalse();
        assertThat(oracle.supportsReturning()).isFalse();
        assertThat(dm.supportsReturning()).isFalse();
        assertThat(postgres.supportsUpsert()).isTrue();
        assertThat(mysql.supportsUpsert()).isTrue();
        assertThat(oracle.supportsUpsert()).isFalse();
        assertThat(dm.supportsUpsert()).isFalse();
        for (DatabaseDialect dialect : List.of(mysql, oracle, dm)) {
            assertThat(dialect.renderGeneratedKey("id")).isEmpty();
        }
        for (DatabaseDialect dialect : List.of(postgres, mysql, oracle, dm)) {
            assertThat(dialect.supportsSavepoint()).isTrue();
            assertThat(dialect.existsResult("SELECT 1 FROM sample"))
                    .contains("THEN 1 ELSE 0 END").doesNotContain("TRUE", "FALSE", "COUNT");
        }
        assertThat(postgres.emptyInsert("t", "id")).isEqualTo("INSERT INTO t DEFAULT VALUES");
        assertThat(mysql.emptyInsert("t", "id")).isEqualTo("INSERT INTO t () VALUES ()");
        assertThat(oracle.emptyInsert("t", "id")).isEqualTo("INSERT INTO t (id) VALUES (DEFAULT)");
        assertThat(dm.emptyInsert("t", "id")).isEqualTo("INSERT INTO t DEFAULT VALUES");
        assertThat(oracle.existsResult("SELECT 1 FROM t")).endsWith("FROM DUAL");
        assertThat(dm.existsResult("SELECT 1 FROM t")).endsWith("FROM DUAL");
    }
}
