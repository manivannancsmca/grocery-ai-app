package com.grocery.common.export;

import java.io.IOException;
import java.util.List;

/**
 * Where an export's rows come from, batch by batch: the {@link Exporter} writes each batch before the next is read,
 * so a million-row export never holds more than one batch in memory. Database lists use {@link ExportQueries}
 * (keyset batches, each in its own short read-only transaction); a small list already in memory uses {@link #of}.
 *
 * @param <R> the row type
 */
public interface RowSource<R> {

    /** How many rows the export will have (checked against the limits before anything is written). */
    long count();

    /**
     * Hands the rows to {@code handler} in order, batch by batch, until there are no more or the handler returns
     * {@code false}. No transaction or database connection is held while the handler runs.
     */
    void forEachBatch(int batchSize, BatchHandler<? super R> handler) throws IOException;

    /** True when the client chose the order ({@code ?sort=}): such exports have their own, lower row limit. */
    default boolean customSorted() {
        return false;
    }

    @FunctionalInterface
    interface BatchHandler<R> {
        /** @return false to stop (e.g. a row limit was reached) */
        boolean handle(List<? extends R> batch) throws IOException;
    }

    /** A list that is small and already loaded (e.g. a store's suppliers): one batch. */
    static <R> RowSource<R> of(List<? extends R> rows) {
        List<? extends R> copy = List.copyOf(rows);
        return new RowSource<>() {
            @Override
            public long count() {
                return copy.size();
            }

            @Override
            public void forEachBatch(int batchSize, BatchHandler<? super R> handler) throws IOException {
                for (int from = 0; from < copy.size(); from += batchSize) {
                    if (!handler.handle(copy.subList(from, Math.min(copy.size(), from + batchSize)))) {
                        return;
                    }
                }
            }
        };
    }
}
