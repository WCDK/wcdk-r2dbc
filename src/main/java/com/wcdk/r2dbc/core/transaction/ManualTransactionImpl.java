package com.wcdk.r2dbc.core.transaction;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.TransactionDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手动事务实现类。
 * <p>
 * 基于R2DBC Connection实现手动事务管理。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
class ManualTransactionImpl implements ManualTransaction {

    private static final Logger log = LoggerFactory.getLogger(ManualTransactionImpl.class);

    private final Connection connection;

    private final AtomicReference<TransactionStatus> status;

    private final Map<String, SavepointImpl> savepoints;

    private final AtomicInteger savepointCounter;

    private volatile boolean readOnly;

    private volatile int timeoutSeconds;

    private volatile String name;

    private volatile LocalDateTime startTime;

    ManualTransactionImpl(Connection connection) {
        this.connection = connection;
        this.status = new AtomicReference<>(TransactionStatus.NEW);
        this.savepoints = new ConcurrentHashMap<>();
        this.savepointCounter = new AtomicInteger(0);
        this.readOnly = false;
        this.timeoutSeconds = 0;
        this.name = "manual-" + System.currentTimeMillis();
        this.startTime = LocalDateTime.now();
    }

    @Override
    public TransactionStatus getStatus() {
        return status.get();
    }

    /**
     * 设置事务状态（仅用于内部清理）。
     *
     * @param newStatus 新状态
     */
    void setStatus(TransactionStatus newStatus) {
        status.set(newStatus);
    }

    @Override
    public Mono<Boolean> commit() {
        TransactionStatus currentStatus = status.get();
        if (currentStatus == TransactionStatus.COMMITTED) {
            return Mono.error(new IllegalStateException("Transaction [{}] already committed"));
        }
        if (currentStatus == TransactionStatus.ROLLED_BACK) {
            return Mono.error(new IllegalStateException("Transaction [{}] already rolled back, cannot commit"));
        }
        if (currentStatus == TransactionStatus.FAILED) {
            return Mono.error(new IllegalStateException("Transaction [{}] has failed, cannot commit"));
        }
        if (currentStatus != TransactionStatus.ACTIVE) {
            return Mono.error(new IllegalStateException("Transaction is not active, cannot commit"));
        }

        return Mono.from(connection.commitTransaction())
                .doOnSuccess(v -> {
                    status.set(TransactionStatus.COMMITTED);
                    savepoints.values().forEach(SavepointImpl::invalidate);
                    log.debug("Transaction [{}] committed successfully", name);
                })
                .doOnError(e -> {
                    status.set(TransactionStatus.FAILED);
                    log.error("Transaction [{}] commit failed", name, e);
                })
                .then(Mono.just(true));
    }

    @Override
    public Mono<Boolean> rollback() {
        TransactionStatus currentStatus = status.get();
        if (currentStatus == TransactionStatus.ROLLED_BACK) {
            return Mono.error(new IllegalStateException("Transaction [{}] already rolled back"));
        }
        if (currentStatus == TransactionStatus.COMMITTED) {
            return Mono.error(new IllegalStateException("Transaction [{}] already committed, cannot rollback"));
        }
        if (currentStatus != TransactionStatus.ACTIVE && currentStatus != TransactionStatus.FAILED) {
            return Mono.error(new IllegalStateException("Transaction is not active or failed, cannot rollback"));
        }

        return Mono.from(connection.rollbackTransaction())
                .doOnSuccess(v -> {
                    status.set(TransactionStatus.ROLLED_BACK);
                    savepoints.values().forEach(SavepointImpl::invalidate);
                    log.debug("Transaction [{}] rolled back successfully", name);
                })
                .doOnError(e -> {
                    status.set(TransactionStatus.FAILED);
                    log.error("Transaction [{}] rollback failed", name, e);
                })
                .then(Mono.just(true));
    }

    @Override
    public Mono<Savepoint> createSavepoint(String savepointName) {
        if (!isActive()) {
            return Mono.error(new IllegalStateException("Transaction is not active, cannot create savepoint"));
        }

        if (savepointName == null || savepointName.isEmpty()) {
            return Mono.error(new IllegalArgumentException("Savepoint name must not be null or empty"));
        }

        if (!savepointName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            return Mono.error(new IllegalArgumentException(
                    "Savepoint name contains invalid characters: " + savepointName
                            + ". Only alphanumeric characters and underscores are allowed, and must start with a letter or underscore."));
        }

        if (savepoints.containsKey(savepointName)) {
            return Mono.error(new IllegalArgumentException("Savepoint already exists: " + savepointName));
        }

        int id = savepointCounter.incrementAndGet();
        String sql = "SAVEPOINT " + savepointName;

        return Mono.from(connection.createStatement(sql).execute())
                .then(Mono.defer(() -> {
                    SavepointImpl savepoint = new SavepointImpl(savepointName, id);
                    savepoints.put(savepointName, savepoint);
                    log.debug("Savepoint [{}] created in transaction [{}]", savepointName, name);
                    return Mono.just(savepoint);
                }));
    }

    @Override
    public Mono<Boolean> rollbackToSavepoint(Savepoint savepoint) {
        if (!isActive()) {
            return Mono.error(new IllegalStateException("Transaction is not active, cannot rollback to savepoint"));
        }

        if (!savepoints.containsKey(savepoint.getName())) {
            return Mono.error(new IllegalArgumentException("Savepoint not found: " + savepoint.getName()));
        }

        String sql = "ROLLBACK TO SAVEPOINT " + savepoint.getName();

        return Mono.from(connection.createStatement(sql).execute())
                .then(Mono.defer(() -> {
                    savepoints.entrySet().removeIf(entry -> entry.getValue().getId() > savepoint.getId());
                    ((SavepointImpl) savepoint).invalidate();
                    log.debug("Rolled back to savepoint [{}] in transaction [{}]", savepoint.getName(), name);
                    return Mono.just(true);
                }));
    }

    @Override
    public Mono<Boolean> releaseSavepoint(Savepoint savepoint) {
        if (!isActive()) {
            return Mono.error(new IllegalStateException("Transaction is not active, cannot release savepoint"));
        }

        if (!savepoints.containsKey(savepoint.getName())) {
            return Mono.error(new IllegalArgumentException("Savepoint not found: " + savepoint.getName()));
        }

        String sql = "RELEASE SAVEPOINT " + savepoint.getName();

        return Mono.from(connection.createStatement(sql).execute())
                .then(Mono.defer(() -> {
                    savepoints.remove(savepoint.getName());
                    ((SavepointImpl) savepoint).invalidate();
                    log.debug("Savepoint [{}] released in transaction [{}]", savepoint.getName(), name);
                    return Mono.just(true);
                }));
    }

    @Override
    public boolean isCompleted() {
        TransactionStatus currentStatus = status.get();
        return currentStatus == TransactionStatus.COMMITTED
                || currentStatus == TransactionStatus.ROLLED_BACK
                || currentStatus == TransactionStatus.COMPLETED
                || currentStatus == TransactionStatus.FAILED;
    }

    @Override
    public boolean isActive() {
        return status.get() == TransactionStatus.ACTIVE;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        if (status.get() == TransactionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Transaction [{}] is already active, cannot set read-only. " +
                    "Set read-only before the transaction begins.");
        }
        if (!isActive()) {
            throw new IllegalStateException("Transaction is not active, cannot set read-only");
        }
        this.readOnly = readOnly;
    }

    @Override
    public void setTimeout(int timeoutSeconds) {
        if (status.get() == TransactionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Transaction [{}] is already active, cannot set timeout. " +
                    "Set timeout before the transaction begins.");
        }
        if (!isActive()) {
            throw new IllegalStateException("Transaction is not active, cannot set timeout");
        }
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("Timeout must be non-negative, got: " + timeoutSeconds);
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取数据库连接。
     *
     * @return 数据库连接
     */
    @Override
    public Connection getConnection() {
        return connection;
    }

    /**
     * 激活事务。
     * <p>
     * 将事务属性（readOnly、timeout）传递给驱动。
     * 如果驱动不支持某个属性，记录警告日志但不抛出异常。
     *
     * @return 激活完成信号
     */
    Mono<Void> activate() {
        if (status.get() != TransactionStatus.NEW) {
            return Mono.empty();
        }

        TransactionDefinition definition = createTransactionDefinition();
        return Mono.from(connection.beginTransaction(definition))
                .doOnSuccess(v -> {
                    status.set(TransactionStatus.ACTIVE);
                    log.debug("Transaction [{}] activated with definition: readOnly={}, timeout={}s",
                            name, readOnly, timeoutSeconds);
                })
                .doOnError(e -> {
                    log.warn("Failed to begin transaction [{}] with definition, falling back to default begin",
                            name, e);
                })
                .then(Mono.defer(() -> {
                    if (status.get() != TransactionStatus.ACTIVE) {
                        return Mono.from(connection.beginTransaction())
                                .doOnSuccess(v -> status.set(TransactionStatus.ACTIVE))
                                .then();
                    }
                    return Mono.empty();
                }))
                .then();
    }

    /**
     * 创建事务定义。
     * <p>
     * 将当前事务属性映射到 R2DBC TransactionDefinition。
     *
     * @return 事务定义
     */
    @SuppressWarnings("unchecked")
    private TransactionDefinition createTransactionDefinition() {
        return new TransactionDefinition() {
            @Override
            public <T> T getAttribute(io.r2dbc.spi.Option<T> option) {
                if (TransactionDefinition.READ_ONLY.equals(option)) {
                    return (T) Boolean.valueOf(readOnly);
                }
                if (TransactionDefinition.NAME.equals(option)) {
                    return (T) name;
                }
                if (TransactionDefinition.LOCK_WAIT_TIMEOUT.equals(option)) {
                    if (timeoutSeconds > 0) {
                        return (T) Duration.ofSeconds(timeoutSeconds);
                    }
                    return null;
                }
                return null;
            }
        };
    }

    /**
     * 保存点实现类。
     */
    static class SavepointImpl implements Savepoint {

        private final String name;

        private final int id;

        private final LocalDateTime createdAt;

        private volatile boolean valid;

        SavepointImpl(String name, int id) {
            this.name = name;
            this.id = id;
            this.createdAt = LocalDateTime.now();
            this.valid = true;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        void invalidate() {
            this.valid = false;
        }
    }
}
