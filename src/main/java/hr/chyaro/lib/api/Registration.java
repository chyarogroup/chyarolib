package hr.chyaro.lib.api;

/** A tracked registration that can be safely and repeatedly closed. */
@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override
    void close();

    static Registration noop() {
        return () -> { };
    }
}
