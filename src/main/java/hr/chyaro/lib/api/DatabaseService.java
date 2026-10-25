package hr.chyaro.lib.api;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface DatabaseService {
    DatabaseHandle open(String componentId, Path file, List<Migration> migrations) throws Exception;

    interface DatabaseHandle extends AutoCloseable {
        <T> T execute(SqlWork<T> work) throws Exception;
        <T> CompletableFuture<T> submit(SqlWork<T> work);
        void flush();
        Path file();
        @Override void close();
    }

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    record Migration(int version, String id, SqlWork<Void> action) {
        public Migration {
            if (version < 1) throw new IllegalArgumentException("Migration version must be positive");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Migration id is required");
            if (action == null) throw new IllegalArgumentException("Migration action is required");
        }
    }
}
