package com.wcdk.r2dbc.core.transaction;

import io.r2dbc.spi.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

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

    @Override
    public Mono<Boolean> commit() {
        if (!isActive()) {
            return Mono.error(new IllegalStateException("Transaction is not active, cannot commit"));
        }

        return Mono.from(connection.commitTransaction())
                .doOnSuccess(v -> {
                    status.set(TransactionStatus.COMMITTED);
                    log.debug("Transaction [{}] committed successfully", name);
                })
                .doOnError(e -> {
                    status.set(TransactionStatus.FAILED);
                    log.error("Transaction [{}] commit failed", name, e);
                })
                .then(Mono.just(true))
                .onErrorResume(e -> Mono.just(false));
    }

    @Override
    public Mono<Boolean> rollback() {
        if (!isActive() && !status.get().equals(TransactionStatus.COMMITTED)) {
            return Mono.error(new IllegalStateException("Transaction is not active, cannot rollback"));
        }

        return Mono.from(connection.rollbackTransaction())
                .doOnSuccess(v -> {
                    status.set(TransactionStatus.ROLLED_BACK);
                    log.debug("Transaction [{}] rolled back successfully", name);
                })
                .doOnError(e -> {
                    status.set(TransactionStatus.FAILED);
                    log.error("Transaction [{}] rollback failed", name, e);
                })
                .then(Mono.just(true))
                .onErrorResume(e -> Mono.just(false));
    }

    @Override
    public Mono<Savepoint> createSavepoint(String savepointName) {
        if (!isActive()) {
            return Mono.error(new IllegalStateException("Transaction is not active, cannot create savepoint"));
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
        return status.get() == TransactionStatus.NEW || status.get() == TransactionStatus.ACTIVE;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        if (!isActive()) {
            throw new IllegalStateException("Transaction is not active, cannot set read-only");
        }
        this.readOnly = readOnly;
        if (readOnly) {
            status.set(TransactionStatus.READ_ONLY);
        }
    }

    @Override
    public void setTimeout(int timeoutSeconds) {
        if (!isActive()) {
            throw new IllegalStateException("Transaction is not active, cannot set timeout");
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
    Connection getConnection() {
        return connection;
    }

    /**
     * 激活事务。
     */
    void activate() {
        if (status.get() == TransactionStatus.NEW) {
            status.set(TransactionStatus.ACTIVE);
        }
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
