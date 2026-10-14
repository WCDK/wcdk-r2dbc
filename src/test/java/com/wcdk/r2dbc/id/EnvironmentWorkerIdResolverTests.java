package com.wcdk.r2dbc.id;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class EnvironmentWorkerIdResolverTests {
    @Test void missingOrUnknownFallbackFailsWithConfigurationGuidance() {
        for (String fallback : new String[]{null, "", "unknown"}) {
            var resolver = new EnvironmentWorkerIdResolver(key -> key.endsWith("FALLBACK") ? fallback : null);
            assertThatThrownBy(resolver::resolveWorkerId).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("WCDK_R2DBC_WORKER_ID", "snowflake-worker-id", "WorkerIdResolver", "digest");
        }
    }
    @Test void rejectsOutOfRangeAndMalformedNumbersEvenWithFallbackEnabled() {
        for (String value : new String[]{"-1", "1024", "2048", "abc", "9223372036854775808"}) {
            var resolver = new EnvironmentWorkerIdResolver(key -> key.endsWith("FALLBACK") ? "digest" : value);
            assertThatThrownBy(resolver::resolveWorkerId).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WCDK_R2DBC_WORKER_ID", "0", "1023");
        }
        for (long value : new long[]{0, 1023}) {
            assertThat(new EnvironmentWorkerIdResolver(key -> Long.toString(value)).resolveWorkerId()).isEqualTo(value);
        }
    }
    @Test void explicitDigestFallbackWarnsAndKeepsProcessNodeStable(CapturedOutput output) {
        var resolver = new EnvironmentWorkerIdResolver(key -> key.endsWith("FALLBACK") ? " digest " : null);
        long node = resolver.resolveWorkerId();
        assertThat(node).isBetween(0L, 1023L);
        assertThat(resolver.resolveWorkerId()).isEqualTo(node);
        assertThat(output.getAll()).contains("WARN", "显式摘要回退", "跨进程可能冲突");
    }
}
