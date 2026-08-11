package com.wcdk.r2dbc.id;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 雪花算法 ID 生成器。
 *
 * @author WCDK
 **/
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1577808000000L;

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

    private static final long DEFAULT_WORKER_ID = resolveWorkerId();

    private final long workerId;
    private long sequence;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        this(DEFAULT_WORKER_ID);
    }

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("工作ID必须在0到" + MAX_WORKER_ID + "之间");
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            Long offset = lastTimestamp - timestamp;
            if (offset > 1000) {
                throw new IllegalStateException("时钟回拨超过1秒");
            }
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private static long resolveWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = System.getenv().getOrDefault("HOSTNAME", "localhost");
        }
        String instanceIdentity = host + ':' + ProcessHandle.current().pid();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(instanceIdentity.getBytes(StandardCharsets.UTF_8));
            long value = ((digest[0] & 0xffL) << 8) | (digest[1] & 0xffL);
            return value & MAX_WORKER_ID;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }
}
