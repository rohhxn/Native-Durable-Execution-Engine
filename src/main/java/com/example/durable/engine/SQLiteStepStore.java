package com.example.durable.engine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SQLiteStepStore {
    private static final Logger log = LoggerFactory.getLogger(SQLiteStepStore.class);
    private static final int MAX_BUSY_RETRIES = 5;
    private static final Duration BUSY_BACKOFF = Duration.ofMillis(200);

    private final String jdbcUrl;
    private final Duration staleInProgressAfter;

    public SQLiteStepStore(String jdbcUrl, Duration staleInProgressAfter) {
        this.jdbcUrl = jdbcUrl;
        this.staleInProgressAfter = staleInProgressAfter;
        init();
    }

    private void init() {
        withRetry(conn -> {
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA busy_timeout=5000");
                    stmt.execute(
                            "CREATE TABLE IF NOT EXISTS steps (" +
                                    "workflow_id TEXT NOT NULL," +
                                    "step_key TEXT NOT NULL," +
                                    "step_id TEXT NOT NULL," +
                                    "sequence INTEGER NOT NULL," +
                                    "status TEXT NOT NULL," +
                                    "output TEXT," +
                                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                                    "PRIMARY KEY (workflow_id, step_key))");
                }
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialize SQLite", e);
            }
        });
    }

    public Optional<StepRecord> find(String workflowId, String stepKey) {
        return withRetry(conn -> {
            try {
                return select(workflowId, stepKey, conn);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query step", e);
            }
        });
    }

    void insertInProgress(StepRecord record, Connection conn) throws SQLException {
        insert(record, conn);
    }

    void updateStatus(StepRecord record, Connection conn) throws SQLException {
        update(record, conn);
    }

    public void insertInProgress(StepRecord record) {
        withRetry(conn -> {
            try {
                return insert(record, conn);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert step", e);
            }
        });
    }

    public void updateStatus(StepRecord record) {
        withRetry(conn -> {
            try {
                return update(record, conn);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to update step", e);
            }
        });
    }

    public void markFailedIfStale(StepRecord record) {
        Instant cutoff = Instant.now().minus(staleInProgressAfter);
        if (record.getStatus() == StepStatus.IN_PROGRESS && record.getUpdatedAt().isBefore(cutoff)) {
            log.warn("Marking stale IN_PROGRESS step as FAILED: {}", record.getStepKey());
            updateStatus(record.withStatus(StepStatus.FAILED, record.getOutput()));
        }
    }

    public <T> T withTransaction(SqlFunction<Connection, T> work) {
        return withRetry(conn -> {
            try {
                conn.setAutoCommit(false);
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException re) {
                    log.error("Rollback failed", re);
                }
                if (e instanceof SQLException se) {
                    if (isBusy(se)) {
                        // Propagate busy to be retried by withRetry
                        throw se;
                    }
                    throw new IllegalStateException("SQLite transaction failed", se);
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignore) {
                    // ignored
                }
            }
        });
    }

    private StepRecord mapRow(ResultSet rs) throws SQLException {
        return new StepRecord(
                rs.getString("workflow_id"),
                rs.getString("step_key"),
                rs.getString("step_id"),
                rs.getLong("sequence"),
                StepStatus.valueOf(rs.getString("status")),
                rs.getString("output"),
                rs.getTimestamp("updated_at").toInstant());
    }

    Optional<StepRecord> select(String workflowId, String stepKey, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT workflow_id, step_key, step_id, sequence, status, output, updated_at " +
                        "FROM steps WHERE workflow_id=? AND step_key=?")) {
            ps.setString(1, workflowId);
            ps.setString(2, stepKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    private Void insert(StepRecord record, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO steps (workflow_id, step_key, step_id, sequence, status, output, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, record.getWorkflowId());
            ps.setString(2, record.getStepKey());
            ps.setString(3, record.getStepId());
            ps.setLong(4, record.getSequence());
            ps.setString(5, record.getStatus().name());
            ps.setString(6, record.getOutput());
            ps.setTimestamp(7, Timestamp.from(record.getUpdatedAt()));
            ps.executeUpdate();
            return null;
        }
    }

    private Void update(StepRecord record, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE steps SET status=?, output=?, updated_at=? " +
                        "WHERE workflow_id=? AND step_key=?")) {
            ps.setString(1, record.getStatus().name());
            ps.setString(2, record.getOutput());
            ps.setTimestamp(3, Timestamp.from(record.getUpdatedAt()));
            ps.setString(4, record.getWorkflowId());
            ps.setString(5, record.getStepKey());
            ps.executeUpdate();
            return null;
        }
    }

    private <T> T withRetry(SqlFunction<Connection, T> work) {
        int attempt = 0;
        while (true) {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                conn.setAutoCommit(true);
                return work.apply(conn);
            } catch (SQLException e) {
                if (isBusy(e)) {
                    if (attempt >= MAX_BUSY_RETRIES) {
                        throw new IllegalStateException("SQLite busy after retries", e);
                    }
                    attempt++;
                    try {
                        Thread.sleep(BUSY_BACKOFF.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during backoff", ie);
                    }
                    continue;
                }
                throw new IllegalStateException("SQLite operation failed", e);
            }
        }
    }

    private boolean isBusy(SQLException e) {
        return "SQLITE_BUSY".equals(e.getSQLState()) || (e.getMessage() != null && e.getMessage().contains("database is locked"));
    }

    @FunctionalInterface
    interface SqlFunction<T, R> {
        R apply(T t) throws SQLException;
    }
}
