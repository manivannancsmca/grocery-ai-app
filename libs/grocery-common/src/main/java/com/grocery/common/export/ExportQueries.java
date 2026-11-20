package com.grocery.common.export;

import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportSort.Order;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Database row sources for exports, reusing the list endpoint's own query (the same filters, so an export shows
 * exactly what the list shows) and reading it in keyset batches:
 * <ul>
 *   <li>each batch is "the next N rows after the last one seen" ({@code where key < :last order by key limit N}),
 *       so batch 1,000 is as fast as batch 1 — never {@code OFFSET}, which re-reads every skipped row;</li>
 *   <li>each batch is read and turned into rows in its own short read-only transaction, and no connection is held
 *       while the rows are written to a (possibly slow) client: normal API traffic keeps its connection pool;</li>
 *   <li>the entities of a batch are released with its transaction, so memory stays flat for any number of rows.</li>
 * </ul>
 * Two kinds of list queries are supported: a JPQL {@code from … where …} text (share it with the repository's
 * {@code @Query} through a constant) and a Spring Data {@link Specification}.
 *
 * <p>An export must not run inside a transaction (that one would collect every entity): call
 * {@link Exporter#write} from the controller, not from a {@code @Transactional} method.
 */
public final class ExportQueries {

    /** The first {@code where} of a {@code from … where …} text: the list's filters start after it. */
    private static final Pattern WHERE = Pattern.compile("(?i)\\bwhere\\b");
    private static final Pattern UNSUPPORTED = Pattern.compile("(?i)\\b(order\\s+by|group\\s+by|having)\\b");

    private final ObjectProvider<EntityManagerFactory> entityManagerFactory;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;
    private volatile EntityManager entityManager;

    public ExportQueries(ObjectProvider<EntityManagerFactory> entityManagerFactory,
                         ObjectProvider<PlatformTransactionManager> transactionManager) {
        this.entityManagerFactory = entityManagerFactory;
        this.transactionManager = transactionManager;
    }

    /**
     * A JPQL list query.
     *
     * @param resultType what {@code select} returns (an entity, or a projection with a constructor expression)
     * @param select     the select expression without {@code select}: {@code "d"}, {@code "distinct u"},
     *                   {@code "new com.example.Row(s, p)"} (then set {@link JpqlRows#countOf})
     * @param fromWhere  {@code from … [where …]} with named parameters; no {@code order by} (the export orders it)
     */
    public <E> JpqlRows<E> jpql(Class<E> resultType, String select, String fromWhere) {
        return new JpqlRows<>(resultType, select, fromWhere);
    }

    /** A Specification list query on {@code repository} (attribute paths in the keys: {@code "createdAt"}). */
    public <E> SpecificationRows<E> specification(JpaSpecificationExecutor<E> repository, Specification<E> filter) {
        return new SpecificationRows<>(repository, filter);
    }

    private EntityManager entityManager() {
        EntityManager current = entityManager;
        if (current == null) {
            // The shared, transaction-bound proxy: inside each batch's transaction it is that transaction's manager.
            current = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory.getObject());
            entityManager = current;
        }
        return current;
    }

    private TransactionTemplate readOnly() {
        TransactionTemplate template = new TransactionTemplate(transactionManager.getObject());
        template.setReadOnly(true);
        return template;
    }

    /** The part shared by both query kinds: the order, the batch loop, the mapping inside the transaction. */
    abstract static sealed class KeysetRows<E, Self extends KeysetRows<E, Self>> permits JpqlRows, SpecificationRows {

        Order<E> order;

        /** The export's order (from {@link ExportSort#resolve}); required. */
        @SuppressWarnings("unchecked")
        public Self orderBy(Order<E> order) {
            this.order = Objects.requireNonNull(order, "order");
            return (Self) this;
        }

        @SuppressWarnings("unchecked")
        @SafeVarargs
        public final Self orderBy(Key<E>... keys) {
            return orderBy(Order.of(keys));
        }

        /** The rows, mapping each fetched result to a row (inside the batch's transaction: lazy loads work). */
        public <R> RowSource<R> rows(Function<? super E, ? extends R> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return batches(results -> results.stream().<R>map(mapper).toList());
        }

        /**
         * The rows, mapping a whole batch at once (inside its transaction): for views that look up related data for
         * a page at a time (e.g. names by id), so a batch costs a few queries, not one per row.
         */
        public <R> RowSource<R> batches(Function<List<E>, List<? extends R>> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            if (order == null) {
                throw new IllegalStateException("Call orderBy(…) before rows(…): an export reads in a fixed order");
            }
            Order<E> keysetOrder = order;
            return new RowSource<>() {
                @Override
                public long count() {
                    requireNoTransaction();
                    Long count = readOnlyTemplate().execute(status -> countRows());
                    return count == null ? 0 : count;
                }

                @Override
                public void forEachBatch(int batchSize, BatchHandler<? super R> handler) throws IOException {
                    requireNoTransaction();
                    TransactionTemplate template = readOnlyTemplate();
                    Object[] after = null;
                    while (true) {
                        Object[] boundary = after;
                        Batch<R> batch = template.execute(status -> {
                            List<E> results = fetch(keysetOrder, boundary, batchSize);
                            List<? extends R> rows = mapper.apply(results);
                            Batch<R> read = new Batch<R>(rows,
                                    results.isEmpty() ? null : keyOf(keysetOrder, results.getLast()), results.size());
                            // Forget the batch's entities: harmless with a transaction-scoped persistence context,
                            // essential where open-session-in-view keeps one for the whole request.
                            clearPersistenceContext();
                            return read;
                        });
                        if (batch == null || batch.size() == 0 || !handler.handle(batch.rows())
                                || batch.size() < batchSize) {
                            return;
                        }
                        after = batch.last();
                    }
                }

                @Override
                public boolean customSorted() {
                    return keysetOrder.custom();
                }
            };
        }

        abstract TransactionTemplate readOnlyTemplate();

        abstract void clearPersistenceContext();

        abstract long countRows();

        abstract List<E> fetch(Order<E> order, Object[] after, int size);

        private static <E> Object[] keyOf(Order<E> order, E last) {
            List<Key<E>> keys = order.keys();
            Object[] values = new Object[keys.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = keys.get(i).value().apply(last);
                if (values[i] == null) {
                    throw new IllegalStateException("Export key '" + keys.get(i).path()
                            + "' is null: only never-null columns can order an export");
                }
            }
            return values;
        }

        private static void requireNoTransaction() {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Exports read in their own short transactions: call Exporter.write "
                        + "outside @Transactional code (one long transaction would hold every row in memory)");
            }
        }
    }

    private record Batch<R>(List<? extends R> rows, Object[] last, int size) {
    }

    /** A JPQL list query; bind the same parameters the list binds. */
    public final class JpqlRows<E> extends KeysetRows<E, JpqlRows<E>> {

        private final Class<E> resultType;
        private final String select;
        private final String head;
        private final String filters;
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private String countOf;

        private JpqlRows(Class<E> resultType, String select, String fromWhere) {
            this.resultType = Objects.requireNonNull(resultType, "resultType");
            this.select = Objects.requireNonNull(select, "select").trim();
            if (UNSUPPORTED.matcher(fromWhere).find()) {
                throw new IllegalArgumentException("Pass the from/where part only: the export adds its own order by");
            }
            Matcher where = WHERE.matcher(fromWhere);
            if (where.find()) {
                this.head = fromWhere.substring(0, where.start()).trim();
                this.filters = fromWhere.substring(where.end()).trim();
            } else {
                this.head = fromWhere.trim();
                this.filters = null;
            }
            this.countOf = this.select.regionMatches(true, 0, "new ", 0, 4) ? null : this.select;
        }

        /** Binds a named parameter of the query (null values too: {@code :status is null or …}). */
        public JpqlRows<E> param(String name, Object value) {
            parameters.put(name, value);
            return this;
        }

        /** What to count when {@code select} is a constructor expression, e.g. {@code "s"}. */
        public JpqlRows<E> countOf(String expression) {
            this.countOf = Objects.requireNonNull(expression, "expression");
            return this;
        }

        @Override
        TransactionTemplate readOnlyTemplate() {
            return readOnly();
        }

        @Override
        void clearPersistenceContext() {
            entityManager().clear();
        }

        @Override
        long countRows() {
            if (countOf == null) {
                throw new IllegalStateException("Set countOf(…) for a constructor-expression select");
            }
            TypedQuery<Long> query = entityManager().createQuery(
                    "select count(" + countOf + ") " + head + (filters == null ? "" : " where " + filters), Long.class);
            parameters.forEach(query::setParameter);
            return query.getSingleResult();
        }

        @Override
        List<E> fetch(Order<E> order, Object[] after, int size) {
            TypedQuery<E> query = entityManager().createQuery(text(order, after != null), resultType);
            parameters.forEach(query::setParameter);
            if (after != null) {
                for (int i = 0; i < after.length; i++) {
                    query.setParameter("exportAfter" + i, after[i]);
                }
            }
            return query.setMaxResults(size).getResultList();
        }

        /**
         * {@code select … from … where (<boundary>) and (<the list's filters>) order by …}: the filters keep their
         * own parentheses, so an {@code or} in them never escapes the boundary.
         */
        String text(Order<E> order, boolean afterBoundary) {
            StringBuilder text = new StringBuilder("select ").append(select).append(' ').append(head);
            List<String> conditions = new ArrayList<>(2);
            if (afterBoundary) {
                conditions.add(boundary(order.keys()));
            }
            if (filters != null) {
                conditions.add(filters);
            }
            for (int i = 0; i < conditions.size(); i++) {
                text.append(i == 0 ? " where (" : " and (").append(conditions.get(i)).append(')');
            }
            text.append(" order by ");
            for (int i = 0; i < order.keys().size(); i++) {
                Key<E> key = order.keys().get(i);
                text.append(i == 0 ? "" : ", ").append(key.path()).append(key.descending() ? " desc" : " asc");
            }
            return text.toString();
        }

        /**
         * {@code k0 <= :a0 and (k0 < :a0 or (k0 = :a0 and k1 < :a1))} for two descending keys: the leading
         * {@code k0 <= :a0} lets the database use an index range on the first key.
         */
        private static <E> String boundary(List<Key<E>> keys) {
            Key<E> first = keys.getFirst();
            return first.path() + (first.descending() ? " <= " : " >= ") + ":exportAfter0 and " + beyond(keys, 0);
        }

        private static <E> String beyond(List<Key<E>> keys, int index) {
            Key<E> key = keys.get(index);
            String strictly = key.path() + (key.descending() ? " < " : " > ") + ":exportAfter" + index;
            if (index == keys.size() - 1) {
                return strictly;
            }
            return "(" + strictly + " or (" + key.path() + " = :exportAfter" + index + " and "
                    + beyond(keys, index + 1) + "))";
        }
    }

    /** A Specification list query. */
    public final class SpecificationRows<E> extends KeysetRows<E, SpecificationRows<E>> {

        private final JpaSpecificationExecutor<E> repository;
        private final Specification<E> filter;

        private SpecificationRows(JpaSpecificationExecutor<E> repository, Specification<E> filter) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.filter = filter == null ? Specification.unrestricted() : filter;
        }

        @Override
        TransactionTemplate readOnlyTemplate() {
            return readOnly();
        }

        @Override
        void clearPersistenceContext() {
            entityManager().clear();
        }

        @Override
        long countRows() {
            return repository.count(filter);
        }

        @Override
        List<E> fetch(Order<E> order, Object[] after, int size) {
            Specification<E> query = after == null ? filter : filter.and(after(order.keys(), after));
            Sort sort = Sort.by(order.keys().stream()
                    .map(key -> key.descending() ? Sort.Order.desc(key.path()) : Sort.Order.asc(key.path()))
                    .toList());
            return repository.<E, List<E>>findBy(query, fluent -> fluent.sortBy(sort).limit(size).all());
        }

        /** The same boundary as the JPQL one, with the Criteria API. */
        private Specification<E> after(List<Key<E>> keys, Object[] values) {
            return (root, query, builder) -> builder.and(
                    compare(builder, path(root, keys.getFirst()), values[0], keys.getFirst().descending(), true),
                    beyond(root, builder, keys, values, 0));
        }

        private Predicate beyond(Root<E> root, CriteriaBuilder builder, List<Key<E>> keys, Object[] values,
                                 int index) {
            Key<E> key = keys.get(index);
            Expression<?> path = path(root, key);
            Predicate strictly = compare(builder, path, values[index], key.descending(), false);
            if (index == keys.size() - 1) {
                return strictly;
            }
            return builder.or(strictly,
                    builder.and(builder.equal(path, values[index]), beyond(root, builder, keys, values, index + 1)));
        }

        private static <E> Path<?> path(Root<E> root, Key<E> key) {
            Path<?> path = root;
            for (String attribute : key.path().split("\\.")) {
                path = path.get(attribute);
            }
            return path;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Predicate compare(CriteriaBuilder builder, Expression path, Object value, boolean descending,
                                         boolean orEqual) {
            Comparable comparable = (Comparable) value;
            if (descending) {
                return orEqual ? builder.lessThanOrEqualTo(path, comparable) : builder.lessThan(path, comparable);
            }
            return orEqual ? builder.greaterThanOrEqualTo(path, comparable) : builder.greaterThan(path, comparable);
        }
    }
}
