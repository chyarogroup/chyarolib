package hr.chyaro.lib.database;

import hr.chyaro.lib.api.DatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SqliteDatabaseServiceTest {
    @TempDir Path temporary;

    @Test
    void appliesMigrationsExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SqliteDatabaseService service = new SqliteDatabaseService(Logger.getAnonymousLogger(), 1000);
        Path file = temporary.resolve("idempotent.db");
        List<DatabaseService.Migration> migrations = List.of(new DatabaseService.Migration(1, "create-values", connection -> {
            calls.incrementAndGet();
            connection.createStatement().execute("CREATE TABLE values_table(value INTEGER NOT NULL)");
            connection.createStatement().execute("INSERT INTO values_table VALUES(7)");
            return null;
        }));
        service.open("test", file, migrations).close();
        DatabaseService.DatabaseHandle reopened = service.open("test", file, migrations);
        assertEquals(1, calls.get());
        int count = reopened.execute(connection -> {
            var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM values_table");
            return result.next() ? result.getInt(1) : 0;
        });
        assertEquals(1, count);
        service.close();
    }

    @Test
    void rollsBackFailedMigration() throws Exception {
        SqliteDatabaseService service = new SqliteDatabaseService(Logger.getAnonymousLogger(), 1000);
        Path file = temporary.resolve("rollback.db");
        DatabaseService.Migration first = new DatabaseService.Migration(1, "base", connection -> {
            connection.createStatement().execute("CREATE TABLE audit(value TEXT)"); return null;
        });
        service.open("rollback", file, List.of(first)).close();
        DatabaseService.Migration failed = new DatabaseService.Migration(2, "fails", connection -> {
            connection.createStatement().execute("INSERT INTO audit VALUES('should-rollback')");
            throw new IllegalStateException("interrupted migration");
        });
        assertThrows(IllegalStateException.class, () -> service.open("rollback", file, List.of(first, failed)));
        DatabaseService.DatabaseHandle reopened = service.open("rollback", file, List.of(first));
        int count = reopened.execute(connection -> {
            var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM audit");
            return result.next() ? result.getInt(1) : -1;
        });
        assertEquals(0, count);
        service.close();
    }
}
