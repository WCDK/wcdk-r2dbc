package com.wcdk.r2dbc.id;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;
import org.slf4j.LoggerFactory;

/**
 * 优先读取 WCDK_R2DBC_WORKER_ID，缺省使用主机与进程摘要并提示跨节点冲突风险。
 * @author wcdk
 */
public final class EnvironmentWorkerIdResolver implements WorkerIdResolver {
    private final Function<String, String> environment;

    public EnvironmentWorkerIdResolver() { this(System::getenv); }

    public EnvironmentWorkerIdResolver(Function<String, String> environment) {
        this.environment = java.util.Objects.requireNonNull(environment);
    }

    @Override
    public long resolveWorkerId() {
        String configured = environment.apply("WCDK_R2DBC_WORKER_ID");
        if (configured != null && !configured.isBlank()) {
            try { return Long.parseLong(configured.trim()); }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("WCDK_R2DBC_WORKER_ID 必须是 0 到 1023 的整数", exception);
            }
        }
        return Fallback.WORKER_ID;
    }

    private static final class Fallback {
        private static final long WORKER_ID = resolve();
        private static long resolve() {
            try {
                String host;
                try { host = InetAddress.getLocalHost().getHostName(); }
                catch (Exception exception) { host = System.getenv().getOrDefault("HOSTNAME", "localhost"); }
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                        (host + ':' + ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8));
                long workerId = (((digest[0] & 255) << 8) | (digest[1] & 255)) & 1023;
                LoggerFactory.getLogger(EnvironmentWorkerIdResolver.class).warn(
                        "雪花节点使用摘要回退 workerId={}，跨进程可能冲突；生产环境请配置唯一节点编号或提供持有独占租约的 WorkerIdResolver", workerId);
                return workerId;
            } catch (Exception exception) {
                throw new IllegalStateException("无法解析雪花节点编号", exception);
            }
        }
    }
}
