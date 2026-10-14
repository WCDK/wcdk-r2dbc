package com.wcdk.r2dbc.execution.log;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class R2dbcSqlLoggerTests {

    private final R2dbcSqlLogger logger =
            new R2dbcSqlLogger(new WcdkR2dbcProperties(), new WcdkSpringR2dbcProperties());

    @Test
    void preservesSensitiveAndLargeParametersWithoutTruncation() {
        var values = Map.of("password", "do-not-log", "accessToken", "also-secret",
                "payload", "x".repeat(300), "ids", java.util.stream.IntStream.range(0, 30).boxed().toList());
        assertThat(logger.logParameters(values)).containsAllEntriesOf(values);
        String sql = logger.completeSql("SELECT :password, :payload, :ids", values);
        assertThat(sql).contains("'do-not-log'", "x".repeat(300), "28, 29").doesNotContain("[REDACTED]", "[truncated]");
    }

    @Test
    void createsExecutableSqlWithEscapedLiterals() {
        String sql = "SELECT * FROM users WHERE id = :id AND name = :name "
                + "AND enabled = :enabled AND deleted_at = :deletedAt AND role IN (:roles)";

        assertThat(logger.completeSql(sql, Map.of(
                "id", 7L,
                "name", "O'Reilly",
                "enabled", true,
                "deletedAt", com.wcdk.r2dbc.execution.SqlParameter.nullOf(String.class),
                "roles", List.of("admin", "operator"))))
                .isEqualTo("SELECT * FROM users WHERE id = 7 AND name = 'O''Reilly' "
                        + "AND enabled = TRUE AND deleted_at = NULL AND role IN ('admin', 'operator')");
    }

    @Test
    void ignoresParameterLikeTextInSqlSyntaxAndPreservesSensitiveValues() {
        String sql = "SELECT ':ignored', value::text, $$:dollar$$ FROM users "
                + "WHERE id = :id AND password = :password /* :comment */";

        assertThat(logger.completeSql(sql, Map.of("id", 9, "password", "do-not-log")))
                .isEqualTo("SELECT ':ignored', value::text, $$:dollar$$ FROM users "
                        + "WHERE id = 9 AND password = 'do-not-log' /* :comment */")
                .contains("do-not-log");
    }

    @Test
    void preservesAllValuesInMultiRowAndFunctionInserts() {
        assertThat(logger.completeSql("INSERT INTO users(password) VALUES(:p0),(:p1)",
                Map.of("p0", "first-secret", "p1", "second-secret")))
                .isEqualTo("INSERT INTO users(password) VALUES('first-secret'),('second-secret')");
        assertThat(logger.completeSql("INSERT INTO users(created_at,password) VALUES(now(),:p0)",
                Map.of("p0", "O'Reilly")))
                .isEqualTo("INSERT INTO users(created_at,password) VALUES(now(),'O''Reilly')");
    }

    @Test
    void rendersIndexedMarkersWithoutChangingJsonOperatorsOrQuotedText() {
        assertThat(logger.completeSql("UPDATE t SET password=? WHERE id=?", Map.of(0L, "secret", 1L, 7)))
                .isEqualTo("UPDATE t SET password='secret' WHERE id=7");
        assertThat(logger.completeSql("SELECT $1, $2, $1, $$ $1 $$", Map.of(0, "secret", 1, 7)))
                .isEqualTo("SELECT 'secret', 7, 'secret', $$ $1 $$");
        assertThat(logger.completeSql("SELECT :1, :2, q'[ :1 ]'", Map.of(0, "secret", 1, 7)))
                .isEqualTo("SELECT 'secret', 7, q'[ :1 ]'");
        assertThat(logger.completeSql("SELECT payload ? 'key', payload ?| array['key'] FROM t WHERE id=$1", Map.of(0, 7)))
                .isEqualTo("SELECT payload ? 'key', payload ?| array['key'] FROM t WHERE id=7");
    }

    @Test
    void preservesWhitespaceInValuesAndLineCommentBoundaries() {
        String sql = "SELECT :value -- comment\nFROM t WHERE id=:id";
        assertThat(logger.completeSql(sql, Map.of("value", "a  b\nc", "id", 7)))
                .isEqualTo("SELECT 'a  b\nc' -- comment\nFROM t WHERE id=7");
        assertThat(logger.normalizeSql(sql)).isEqualTo(sql);
        assertThat(logger.completeSql("SELECT :bytes", Map.of("bytes", new byte[]{0, 15})))
                .isEqualTo("SELECT X'000f'");
    }
}
