package com.wcdk.r2dbc.transaction;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.TransactionDefinition;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手动事务实现。
 *
 * @author WCDK
 */
class ManualTransactionImpl implements ManualTransaction {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ManualTransactionImpl.class);

    private final Connection connection;
    private final AtomicReference<TransactionStatus> status = new AtomicReference<>(TransactionStatus.NEW);
    private final Map<String, SavepointImpl> savepoints = new ConcurrentHashMap<>();
    private final AtomicInteger savepointCounter = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    // cache 保持已发起的驱动操作运行，取消订阅只撤销等待，不能取消提交后并发回滚。
    private final Mono<Boolean> commitCompletion = Mono.defer(() -> Mono.from(getConnection().commitTransaction()))
            .doOnSuccess(ignored -> finish(TransactionStatus.COMMITTED))
            .doOnError(error -> finish(TransactionStatus.FAILED)).thenReturn(true).cache();
    private final Mono<Boolean> rollbackCompletion = Mono.defer(() -> Mono.from(getConnection().rollbackTransaction()))
            .doOnSuccess(ignored -> finish(TransactionStatus.ROLLED_BACK))
            .doOnError(error -> finish(TransactionStatus.FAILED)).thenReturn(true).cache();
    private final Mono<Void> closeCompletion = Mono.defer(this::closeOnce).cache();
    private volatile boolean readOnly;
    private volatile int timeoutSeconds;
    private volatile IsolationLevel isolationLevel;
    private volatile String name = "manual-" + System.currentTimeMillis();

    ManualTransactionImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public TransactionStatus getStatus() {
        return status.get();
    }

    void setStatus(TransactionStatus newStatus) {
        status.set(newStatus);
    }

    @Override
    public Mono<Boolean> commit() {
        return Mono.defer(() -> {
            if (closed.get() || !status.compareAndSet(TransactionStatus.ACTIVE, TransactionStatus.COMMITTING)) {
                return Mono.error(new IllegalStateException("事务状态为 " + status.get() + "，无法提交"));
            }
            return commitCompletion;
        });
    }

    @Override
    public Mono<Boolean> rollback() {
        return Mono.defer(() -> {
            TransactionStatus current = status.get();
            if (closed.get() || (current != TransactionStatus.ACTIVE && current != TransactionStatus.FAILED)
                    || !status.compareAndSet(current, TransactionStatus.CLOSING)) {
                return Mono.error(new IllegalStateException("事务状态为 " + status.get() + "，无法回滚"));
            }
            return rollbackCompletion;
        });
    }

    private void finish(TransactionStatus terminal) {
        invalidateAllSavepoints();
        status.set(terminal);
    }

    @Override
    public Mono<Savepoint> createSavepoint(String savepointName) {
        return Mono.defer(() -> {
            if (!isActive()) {
                return Mono.error(new IllegalStateException("Transaction is not active, cannot create savepoint"));
            }
            if (savepointName == null || !savepointName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                return Mono.error(new IllegalArgumentException("Invalid savepoint name: " + savepointName));
            }
            SavepointImpl savepoint = new SavepointImpl(savepointName, savepointCounter.incrementAndGet());
            savepoint.invalidate(); // 在驱动完成前仅作为预占位，不可回滚/释放。
            if (savepoints.putIfAbsent(savepointName, savepoint) != null) {
                return Mono.error(new IllegalArgumentException("保存点已存在或正在创建: " + savepointName));
            }
            return Mono.defer(() -> Mono.from(connection.createSavepoint(savepointName)))
                    .doOnSuccess(ignored -> savepoint.valid = true)
                    .doOnError(error -> savepoints.remove(savepointName, savepoint))
                    .thenReturn((Savepoint) savepoint)
                    .cache(); // 创建在途取消仍完成登记，避免驱动留下未登记保存点。

        });
    }

    @Override
    public Mono<Boolean> rollbackToSavepoint(Savepoint savepoint) {
        return Mono.defer(() -> {
            if (!isActive()) {
                return Mono.error(new IllegalStateException("Transaction is not active, cannot rollback to savepoint"));
            }
            SavepointImpl target = activeSavepoint(savepoint);
            if (target == null) {
                return Mono.error(new IllegalArgumentException("Savepoint not found or no longer valid"));
            }
            return Mono.from(connection.rollbackTransactionToSavepoint(target.getName()))
                    .then(Mono.fromSupplier(() -> {
                        savepoints.entrySet().removeIf(entry -> {
                            boolean remove = entry.getValue().getId() > target.getId();
                            if (remove) {
                                entry.getValue().invalidate();
                            }
                            return remove;
                        });
                        return true;
                    }));
        });
    }

    @Override
    public Mono<Boolean> releaseSavepoint(Savepoint savepoint) {
        return Mono.defer(() -> {
            if (!isActive()) {
                return Mono.error(new IllegalStateException("Transaction is not active, cannot release savepoint"));
            }
            SavepointImpl target = activeSavepoint(savepoint);
            if (target == null) {
                return Mono.error(new IllegalArgumentException("Savepoint not found or no longer valid"));
            }
            return Mono.from(connection.releaseSavepoint(target.getName()))
                    .then(Mono.fromSupplier(() -> {
                        savepoints.remove(target.getName());
                        target.invalidate();
                        return true;
                    }));
        });
    }

    private SavepointImpl activeSavepoint(Savepoint savepoint) {
        if (!(savepoint instanceof SavepointImpl target) || !target.isValid()) {
            return null;
        }
        return savepoints.get(target.getName()) == target ? target : null;
    }

    private void invalidateAllSavepoints() {
        savepoints.values().forEach(SavepointImpl::invalidate);
        savepoints.clear();
    }

    @Override
    public boolean isCompleted() {
        TransactionStatus current = status.get();
        return current == TransactionStatus.COMMITTED
                || current == TransactionStatus.ROLLED_BACK
                || current == TransactionStatus.COMPLETED
                || current == TransactionStatus.FAILED;
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
        ensureNew();
        this.readOnly = readOnly;
    }

    @Override
    public void setTimeout(int timeoutSeconds) {
        ensureNew();
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("超时时间必须是非负数，当前值: " + timeoutSeconds);
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public IsolationLevel getIsolationLevel() {
        return isolationLevel;
    }

    @Override
    public void setIsolationLevel(IsolationLevel isolationLevel) {
        ensureNew();
        this.isolationLevel = isolationLevel;
    }

    private void ensureNew() {
        if (status.get() != TransactionStatus.NEW) {
            throw new IllegalStateException("事务属性只能在开始前修改");
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        ensureNew();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("事务名称不能为空");
        }
        this.name = name;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public Mono<Void> close() {
        return closeCompletion;
    }

    private Mono<Void> closeOnce() {
        closed.set(true);
        TransactionStatus current = status.get();
        if (current == TransactionStatus.COMMITTING) {
            return commitCompletion.onErrorResume(error -> Mono.empty())
                    .then(Mono.defer(() -> closeConnection(status.get())));
        }
        if (current == TransactionStatus.CLOSING) {
            return rollbackCompletion.onErrorResume(error -> Mono.empty())
                    .then(Mono.defer(() -> closeConnection(status.get())));
        }
        if (!status.compareAndSet(current, TransactionStatus.CLOSING)) {
            return Mono.defer(this::closeOnce);
        }
        if (current == TransactionStatus.ACTIVE || current == TransactionStatus.FAILED) {
            return Mono.defer(() -> Mono.from(getConnection().rollbackTransaction()))
                    .doOnSuccess(ignored -> finish(TransactionStatus.ROLLED_BACK))
                    .onErrorResume(error -> {
                        finish(TransactionStatus.FAILED);
                        log.warn("关闭事务时回滚失败，继续关闭连接", error);
                        return Mono.empty();
                    })
                    .then(Mono.defer(() -> closeConnection(status.get())));
        }
        return closeConnection(current);
    }

    private Mono<Void> closeConnection(TransactionStatus terminal) {
        status.set(TransactionStatus.CLOSING);
        return Mono.defer(() -> Mono.from(connection.close()))
                .doOnSuccess(ignored -> finish(terminal == TransactionStatus.NEW ? TransactionStatus.COMPLETED : terminal))
                .doOnError(error -> finish(TransactionStatus.FAILED));
    }

    Mono<Void> activate() {
        return Mono.defer(() -> {
            if (status.get() != TransactionStatus.NEW) {
                return Mono.error(new IllegalStateException("Transaction has already begun"));
            }
            return Mono.from(connection.beginTransaction(createTransactionDefinition()))
                    .doOnSuccess(ignored -> status.set(TransactionStatus.ACTIVE))
                    .doOnError(error -> status.set(TransactionStatus.FAILED))
                    .then();
        });
    }

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
                    return timeoutSeconds > 0 ? (T) Duration.ofSeconds(timeoutSeconds) : null;
                }
                if (TransactionDefinition.ISOLATION_LEVEL.equals(option)) {
                    return (T) isolationLevel;
                }
                return null;
            }
        };
    }

    static class SavepointImpl implements Savepoint {
        private final String name;
        private final int id;
        private final LocalDateTime createdAt = LocalDateTime.now();
        private volatile boolean valid = true;

        SavepointImpl(String name, int id) {
            this.name = name;
            this.id = id;
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
            valid = false;
        }
    }
}