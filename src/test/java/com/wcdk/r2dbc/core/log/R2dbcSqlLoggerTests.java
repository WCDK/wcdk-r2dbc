package com.wcdk.r2dbc.core.log;

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
    void redactsSensitiveValuesAndBoundsLargeParameters() {
        Map<String, Object> result = logger.sanitizeParameters(Map.of(
                "password", "do-not-log",
                "accessToken", "also-secret",
                "payload", "x".repeat(300),
                "ids", java.util.stream.IntStream.range(0, 30).boxed().toList(),
                "blob", new byte[1024]));

        assertThat(result.get("password")).isEqualTo("[REDACTED]");
        assertThat(result.get("accessToken")).isEqualTo("[REDACTED]");
        assertThat(result.get("payload").toString()).hasSizeLessThan(300).endsWith("[truncated]");
        assertThat((List<?>) result.get("ids")).hasSize(21);
        assertThat(((List<?>) result.get("ids")).getLast()).isEqualTo("[truncated]");
        assertThat(result.get("blob")).isEqualTo("[bytes:1024]");
        assertThat(result.toString()).doesNotContain("do-not-log", "also-secret");
    }
}
