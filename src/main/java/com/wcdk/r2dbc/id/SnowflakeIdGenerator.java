package com.wcdk.r2dbc.id;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * 使用 CAS 分配雪花 ID；同一进程内相同节点共享序列，跨进程节点唯一性由部署配置或注册中心保证。
 * 时钟回拨立即失败，序列耗尽时在锁外短暂等待下一毫秒。
 * @author wcdk
 */
public class SnowflakeIdGenerator {
    private static final long EPOCH = 1577808000000L;
    private static final long MAX_WORKER_ID = 1023L;
    private static final long MAX_SEQUENCE = 4095L;
    private static final long MAX_TIMESTAMP = (1L << 41) - 1;
    private static final ConcurrentHashMap<Long, AtomicLong> STATES = new ConcurrentHashMap<>();
    private final long workerId;
    private final AtomicLong state;
    private final LongSupplier clock;

    public SnowflakeIdGenerator() { this(new EnvironmentWorkerIdResolver().resolveWorkerId()); }

    public SnowflakeIdGenerator(long workerId) {
        this(workerId, System::currentTimeMillis, sharedState(workerId));
    }

    // 可控时钟仅用于包内验证，避免测试时钟污染生产共享状态。
    SnowflakeIdGenerator(long workerId, LongSupplier clock) {
        this(workerId, clock, new AtomicLong(-1L));
    }

    private SnowflakeIdGenerator(long workerId, LongSupplier clock, AtomicLong state) {
        validateWorkerId(workerId);
        this.workerId = workerId;
        this.clock = java.util.Objects.requireNonNull(clock);
        this.state = state;
    }

    private static AtomicLong sharedState(long workerId) {
        validateWorkerId(workerId);
        return STATES.computeIfAbsent(workerId, ignored -> new AtomicLong(-1L));
    }

    private static void validateWorkerId(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("工作 ID 必须在 0 到 1023 之间：" + workerId);
        }
    }

    public long getWorkerId() { return workerId; }

    public long nextId() {
        while (true) {
            long previous = state.get();
            long timestamp = clock.getAsLong() - EPOCH;
            if (timestamp < 0 || timestamp > MAX_TIMESTAMP) {
                throw new IllegalStateException("当前时间超出雪花 ID 支持范围");
            }
            long lastTimestamp = previous < 0 ? -1 : previous >>> 12;
            if (timestamp < lastTimestamp) {
                throw new IllegalStateException("时钟回拨 " + (lastTimestamp - timestamp)
                        + " 毫秒，停止生成雪花 ID；待时钟恢复后重试");
            }
            long sequence = timestamp == lastTimestamp ? (previous & MAX_SEQUENCE) + 1 : 0;
            if (sequence > MAX_SEQUENCE) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("等待雪花 ID 序列恢复时线程已中断");
                }
                LockSupport.parkNanos(100_000L);
                continue;
            }
            long next = (timestamp << 12) | sequence;
            if (state.compareAndSet(previous, next)) {
                return (timestamp << 22) | (workerId << 12) | sequence;
            }
        }
    }
}
