package com.wcdk.r2dbc.core.transaction;

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

class ManualTransactionImpl implements ManualTransaction {

    private final Connection connection;
    private final AtomicReference<TransactionStatus> status = new AtomicReference<>(TransactionStatus.NEW);
    private final Map<String, SavepointImpl> savepoints = new ConcurrentHashMap<>();
    private final AtomicInteger savepointCounter = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
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
            TransactionStatus current = status.get();
            if (current == TransactionStatus.COMMITTED) {
                return Mono.error(new IllegalStateException("Transaction already committed"));
            }
            if (current == TransactionStatus.ROLLED_BACK) {
                return Mono.error(new IllegalStateException("Transaction already rolled back, cannot commit"));
            }
            if (current == TransactionStatus.FAILED) {
                return Mono.error(new IllegalStateException("Transaction has failed, cannot commit"));
            }
            if (current != TransactionStatus.ACTIVE) {
                return Mono.error(new IllegalStateException("Transaction is not active, cannot commit"));
            }
            return Mono.from(connection.commitTransaction())
                    .doOnSuccess(ignored -> {
                        status.set(TransactionStatus.COMMITTED);
                        invalidateAllSavepoints();
                    })
                    .doOnError(error -> status.set(TransactionStatus.FAILED))
                    .thenReturn(true);
        });
    }

    @Override
    public Mono<Boolean> rollback() {
        return Mono.defer(() -> {
            TransactionStatus current = status.get();
            if (current == TransactionStatus.ROLLED_BACK) {
                return Mono.error(new IllegalStateException("Transaction already rolled back"));
            }
            if (current == TransactionStatus.COMMITTED) {
                return Mono.error(new IllegalStateException("Transaction already committed, cannot rollback"));
            }
            if (current != TransactionStatus.ACTIVE && current != TransactionStatus.FAILED) {
                return Mono.error(new IllegalStateException("Transaction is not active or failed, cannot rollback"));
            }
            return Mono.from(connection.rollbackTransaction())
                    .doOnSuccess(ignored -> {
                        status.set(TransactionStatus.ROLLED_BACK);
                        invalidateAllSavepoints();
                    })
                    .doOnError(error -> status.set(TransactionStatus.FAILED))
                    .thenReturn(true);
        });
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
            if (savepoints.containsKey(savepointName)) {
                return Mono.error(new IllegalArgumentException("Savepoint already exists: " + savepointName));
            }
            return Mono.from(connection.createStatement("SAVEPOINT " + savepointName).execute())
                    .then(Mono.fromSupplier(() -> {
                        SavepointImpl savepoint = new SavepointImpl(savepointName, savepointCounter.incrementAndGet());
                        savepoints.put(savepointName, savepoint);
                        return (Savepoint) savepoint;
                    }));
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
            return Mono.from(connection.createStatement("ROLLBACK TO SAVEPOINT " + target.getName()).execute())
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
            return Mono.from(connection.createStatement("RELEASE SAVEPOINT " + target.getName()).execute())
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
            throw new IllegalArgumentException("Timeout must be non-negative, got: " + timeoutSeconds);
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
            throw new IllegalStateException("Transaction properties can only be changed before begin");
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
            throw new IllegalArgumentException("Transaction name must not be blank");
        }
        this.name = name;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public Mono<Void> close() {
        return Mono.defer(() -> {
            if (!closed.compareAndSet(false, true)) {
                return Mono.empty();
            }
            if (status.get() != TransactionStatus.ACTIVE) {
                return Mono.from(connection.close());
            }
            return rollback().then(Mono.from(connection.close()))
                    .onErrorResume(rollbackError -> Mono.from(connection.close())
                            .onErrorResume(closeError -> {
                                rollbackError.addSuppressed(closeError);
                                return Mono.empty();
                            })
                            .then(Mono.error(rollbackError)));
        });
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