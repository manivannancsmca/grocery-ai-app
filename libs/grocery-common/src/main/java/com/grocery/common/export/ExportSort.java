package com.grocery.common.export;

import com.grocery.common.web.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The orders an export can come in: the list's own default order (newest first, say) and the columns a client may
 * sort by with {@code ?sort=<name>,asc|desc} (a whitelist: anything else is 400 INVALID_PARAMETER). The export is read
 * in batches by keyset ("rows after the last one seen"), never with OFFSET, so every key must be a column that is
 * never null, and the order always ends with the unique id (the tie-breaker).
 * <pre>{@code
 * static final ExportSort<Delivery> SORT = ExportSort.<Delivery>by(
 *                 Key.desc("d.createdAt", Delivery::getCreatedAt), Key.desc("d.id", Delivery::getId))
 *         .option("orderNumber", "d.orderNumber", Delivery::getOrderNumber)
 *         .build();
 * }</pre>
 * Paths are JPQL paths with the query's alias for {@link ExportQueries#jpql}, attribute paths ("createdAt") for
 * {@link ExportQueries#specification}.
 *
 * @param <E> what the query returns (an entity or a projection)
 */
public final class ExportSort<E> {

    private final List<Key<E>> defaultOrder;
    private final Map<String, Option<E>> options;
    private final Key<E> tieBreaker;

    private ExportSort(List<Key<E>> defaultOrder, Map<String, Option<E>> options) {
        this.defaultOrder = List.copyOf(defaultOrder);
        this.options = Map.copyOf(options);
        this.tieBreaker = defaultOrder.getLast();
    }

    /** The default order; its last key must be unique (the id). */
    @SafeVarargs
    public static <E> Builder<E> by(Key<E>... defaultOrder) {
        return new Builder<>(List.of(defaultOrder));
    }

    /** The default order only (no column may be chosen). */
    @SafeVarargs
    public static <E> ExportSort<E> fixed(Key<E>... defaultOrder) {
        return new Builder<>(List.of(defaultOrder)).build();
    }

    /** The names a client may sort by. */
    public List<String> optionNames() {
        return options.keySet().stream().sorted().toList();
    }

    /**
     * The order for a request's {@code sort} parameter: blank → the default; {@code name}, {@code name,asc} or
     * {@code name,desc} → that column then the tie-breaker; anything else → 400 INVALID_PARAMETER.
     */
    public Order<E> resolve(String sort) {
        if (sort == null || sort.isBlank()) {
            return new Order<>(defaultOrder, false);
        }
        String[] parts = sort.trim().split(",", -1);
        Option<E> option = parts.length <= 2 ? options.get(parts[0].trim()) : null;
        if (option == null) {
            throw new BadRequestException("INVALID_PARAMETER", "This export cannot be sorted by '"
                    + abbreviate(parts[0].trim()) + "'" + (options.isEmpty() ? "" : ": use one of " + optionNames()));
        }
        String direction = parts.length == 2 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new BadRequestException("INVALID_PARAMETER", "Sort direction must be asc or desc");
        }
        boolean descending = direction.equals("desc");
        List<Key<E>> keys = new ArrayList<>(2);
        keys.add(new Key<>(option.path(), descending, option.value()));
        if (!option.path().equals(tieBreaker.path())) {
            keys.add(new Key<>(tieBreaker.path(), descending, tieBreaker.value()));
        }
        return new Order<>(keys, true);
    }

    private static String abbreviate(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
    }

    /**
     * One key of the order.
     *
     * @param path       JPQL path ({@code "d.createdAt"}) or attribute path ({@code "createdAt"}); never-null column
     * @param descending newest / largest first
     * @param value      reads the key's value from a fetched row (where the next batch starts)
     */
    public record Key<E>(String path, boolean descending, Function<? super E, ?> value) {

        public Key {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(value, "value");
        }

        public static <E> Key<E> asc(String path, Function<? super E, ?> value) {
            return new Key<>(path, false, value);
        }

        public static <E> Key<E> desc(String path, Function<? super E, ?> value) {
            return new Key<>(path, true, value);
        }
    }

    /** A resolved order: its keys, and whether the client chose it. */
    public record Order<E>(List<Key<E>> keys, boolean custom) {

        public Order {
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("An export order needs at least one key");
            }
            keys = List.copyOf(keys);
        }

        /** A fixed order (not chosen by the client). */
        @SafeVarargs
        public static <E> Order<E> of(Key<E>... keys) {
            return new Order<>(List.of(keys), false);
        }
    }

    private record Option<E>(String path, Function<? super E, ?> value) {
    }

    public static final class Builder<E> {

        private final List<Key<E>> defaultOrder;
        private final Map<String, Option<E>> options = new LinkedHashMap<>();

        private Builder(List<Key<E>> defaultOrder) {
            if (defaultOrder.isEmpty()) {
                throw new IllegalArgumentException("The default order needs at least one key (ending with the id)");
            }
            this.defaultOrder = defaultOrder;
        }

        /** A column the client may sort by: {@code name} as the frontend sends it, the path of a never-null column. */
        public Builder<E> option(String name, String path, Function<? super E, ?> value) {
            options.put(name, new Option<>(path, value));
            return this;
        }

        public ExportSort<E> build() {
            return new ExportSort<>(defaultOrder, options);
        }
    }
}
