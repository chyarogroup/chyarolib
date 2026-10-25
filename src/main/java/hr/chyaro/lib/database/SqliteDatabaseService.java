package hr.chyaro.lib.database;

import hr.chyaro.lib.api.DatabaseService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class SqliteDatabaseService implements DatabaseService, AutoCloseable {
    private final Logger logger;
    private final int busyTimeoutMillis;
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();

    public SqliteDatabaseService(Logger logger, int busyTimeoutMillis) throws ClassNotFoundException {
        this.logger = Objects.requireNonNull(logger);
        this.busyTimeoutMillis = Math.max(1000, busyTimeoutMillis);
        Class.forName("org.sqlite.JDBC");
    }

    @Override
    public DatabaseHandle open(String componentId, Path file, List<Migration> migrations) throws Exception {
        String id = normalizeId(componentId);
        Path normalized = file.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());

        Handle created = new Handle(id, normalized, migrations == null ? List.of() : migrations);
        Handle previous = handles.putIfAbsent(id, created);
        if (previous != null) {
            created.close();
            if (!previous.file().equals(normalized)) {
                throw new IllegalStateException("Database component '" + id + "' is already open at " + previous.file());
            }
            return previous;
        }
        return created;
    }

    public void closeComponent(String componentId) {
        Handle handle = handles.remove(normalizeId(componentId));
        if (handle != null) handle.close();
    }

    @Override
    public void close() {
        handles.values().forEach(Handle::close);
        handles.clear();
    }

    private static String normalizeId(String input) {
        String id = Objects.requireNonNull(input, "componentId").trim().toLowerCase();
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid component id: " + input);
        }
        return id;
    }

    private final class Handle implements DatabaseHandle {
        private final String componentId;
        private final Path file;
        private final Connection connection;
        private final ExecutorService executor;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(String componentId, Path file, List<Migration> migrations) throws Exception {
            this.componentId = componentId;
            this.file = file;
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "chyarolib-db-" + componentId);
                thread.setDaemon(true);
                return thread;
            });
            try {
                configure();
                migrate(migrations);
            } catch (Exception exception) {
                executor.shutdownNow();
                try { connection.close(); } catch (Exception ignored) { }
                throw exception;
            }
        }

        private void configure() throws Exception {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("CREATE TABLE IF NOT EXISTS chyarolib_migrations (" +
                        "version INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, applied_at INTEGER NOT NULL)");
            }
        }

        private void migrate(List<Migration> migrations) throws Exception {
            List<Migration> ordered = migrations.stream().sorted(Comparator.comparingInt(Migration::version)).toList();
            int current = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM chyarolib_migrations")) {
                if (rs.next()) current = rs.getInt(1);
            }

            for (Migration migration : ordered) {
                if (migration.version() <= current) continue;
                boolean oldAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    migration.action().run(connection);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO chyarolib_migrations(version, id, applied_at) VALUES(?, ?, ?)")) {
                        insert.setInt(1, migration.version());
                        insert.setString(2, migration.id());
                        insert.setLong(3, System.currentTimeMillis());
                        insert.executeUpdate();
                    }
                    connection.commit();
                    logger.info("[chyarolib] Applied database migration " + componentId + ":" + migration.id());
                    current = migration.version();
                } catch (Exception exception) {
                    connection.rollback();
                    throw new IllegalStateException("Migration failed for " + componentId + ":" + migration.id(), exception);
                } finally {
                    connection.setAutoCommit(oldAutoCommit);
                }
            }
        }

        @Override
        public synchronized <T> T execute(SqlWork<T> work) throws Exception {
            ensureOpen();
            return work.run(connection);
        }

        @Override
        public <T> CompletableFuture<T> submit(SqlWork<T> work) {
            CompletableFuture<T> future = new CompletableFuture<>();
            if (closed.get()) {
                future.completeExceptionally(new IllegalStateException("Database is closed: " + componentId));
                return future;
            }
            executor.execute(() -> {
                try {
                    future.complete(execute(work));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        }

        @Override
        public void flush() {
            if (closed.get()) return;
            submit(connection -> null).join();
        }

        @Override
        public Path file() {
            return file;
        }

        private void ensureOpen() {
            if (closed.get()) throw new IllegalStateException("Database is closed: " + componentId);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            try {
                connection.close();
            } catch (Exception exception) {
                logger.warning("[chyarolib] Could not close database " + componentId + ": " + exception.getMessage());
            }
            handles.remove(componentId, this);
        }
    }
}
