package com.wcdk.r2dbc.id;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;
import org.slf4j.LoggerFactory;

/**
 * 要求配置 WCDK_R2DBC_WORKER_ID；只有显式开启 digest 开发回退时使用主机与进程摘要。
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
            try {
                long workerId = Long.parseLong(configured.trim());
                if (workerId < 0 || workerId > 1023) {
                    throw new IllegalArgumentException("WCDK_R2DBC_WORKER_ID 必须是 0 到 1023 的整数：" + configured);
                }
                return workerId;
            }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("WCDK_R2DBC_WORKER_ID 必须是 0 到 1023 的整数", exception);
            }
        }
        String fallback = environment.apply("WCDK_R2DBC_WORKER_ID_FALLBACK");
        if (fallback != null && "digest".equalsIgnoreCase(fallback.trim())) {
            long workerId = Fallback.WORKER_ID;
            LoggerFactory.getLogger(EnvironmentWorkerIdResolver.class).warn(
                    "雪花节点使用显式摘要回退 workerId={}，跨进程可能冲突；生产环境请配置唯一节点编号或提供持有独占租约的 WorkerIdResolver", workerId);
            return workerId;
        }
        throw new IllegalStateException("缺少雪花节点编号：请配置 wcdk.r2dbc.snowflake-worker-id 或 WCDK_R2DBC_WORKER_ID（0 到 1023），"
                + "或提供 WorkerIdResolver；开发环境可显式设置 WCDK_R2DBC_WORKER_ID_FALLBACK=digest");
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
                return workerId;
            } catch (Exception exception) {
                throw new IllegalStateException("无法解析雪花节点编号", exception);
            }
        }
    }
}
