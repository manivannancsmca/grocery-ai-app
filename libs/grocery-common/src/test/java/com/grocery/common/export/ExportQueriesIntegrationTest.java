package com.grocery.common.export;

import com.grocery.common.export.ExportSort.Key;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keyset batches against a real MySQL: read 100 rows at a time, an export gets exactly the rows (and the order) of
 * one big query with the same filters, also when many rows share the sort value, when the list's filters contain a
 * top-level {@code or}, and for a client-chosen column.
 */
@Testcontainers
// Closed after the class: an open context keeps Logback initialised, and later tests could not set their log format
@DirtiesContext
@SpringBootTest(classes = ExportQueriesIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "grocery.events.avro.enabled=false",
                "grocery.housekeeping.enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
class ExportQueriesIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    static final int ROWS = 2_503;
    static final int BATCH = 100;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(considerNestedRepositories = true)
    static class App {
    }

    @Entity(name = "ExportItem")
    @Table(name = "export_item")
    public static class Item {
        @Id
        private UUID id;
        @Column(nullable = false)
        private String name;
        @Column(nullable = false)
        private String status;
        @Column(nullable = false)
        private Instant createdAt;
        @Column(nullable = false, precision = 12, scale = 2)
        private BigDecimal amount;

        protected Item() {
        }

        Item(UUID id, String name, String status, Instant createdAt, BigDecimal amount) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.createdAt = createdAt;
            this.amount = amount;
        }

        public UUID getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    public interface ItemRepository extends JpaRepository<Item, UUID>, JpaSpecificationExecutor<Item> {
    }

    @Autowired
    ItemRepository items;
    @Autowired
    ExportQueries queries;
    @Autowired
    PlatformTransactionManager transactions;

    static boolean loaded;

    @BeforeAll
    static void reset() {
        loaded = false;
    }

    void load() {
        if (loaded) {
            return;
        }
        List<Item> all = new ArrayList<>();
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        for (int i = 0; i < ROWS; i++) {
            // 7 rows share each instant and 4 share each amount: the id must break those ties
            all.add(new Item(UUID.randomUUID(), "Item " + (i % 37), i % 3 == 0 ? "OPEN" : "CLOSED",
                    start.plusSeconds(i / 7), BigDecimal.valueOf(i / 4, 1)));
        }
        items.saveAll(all);
        loaded = true;
    }

    static final ExportSort<Item> JPQL_SORT = ExportSort.<Item>by(
                    Key.desc("i.createdAt", Item::getCreatedAt), Key.desc("i.id", Item::getId))
            .option("amount", "i.amount", Item::getAmount)
            .build();

    static final ExportSort<Item> SPEC_SORT = ExportSort.<Item>by(
                    Key.desc("createdAt", Item::getCreatedAt), Key.desc("id", Item::getId))
            .option("amount", "amount", Item::getAmount)
            .build();

    @Test
    void batchesReturnEveryRowOnceInTheListsOrder() throws IOException {
        load();
        RowSource<UUID> source = queries.jpql(Item.class, "i", "from ExportItem i")
                .orderBy(JPQL_SORT.resolve(null))
                .rows(Item::getId);

        assertThat(source.count()).isEqualTo(ROWS);
        assertThat(read(source)).containsExactlyElementsOf(ids(items.findAll(
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))));
    }

    @Test
    void theListsFiltersStayIntactEvenWithATopLevelOr() throws IOException {
        load();
        String filters = "from ExportItem i where :name is null or i.name = :name "
                + "or i.status = :status";
        RowSource<UUID> source = queries.jpql(Item.class, "i", filters)
                .param("name", "Item 5")
                .param("status", "OPEN")
                .orderBy(JPQL_SORT.resolve(null))
                .rows(Item::getId);

        List<Item> expected = new TransactionTemplate(transactions).execute(status -> items.findAll(
                (root, query, builder) -> builder.or(builder.equal(root.get("name"), "Item 5"),
                        builder.equal(root.get("status"), "OPEN")),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        assertThat(source.count()).isEqualTo(Objects.requireNonNull(expected).size());
        assertThat(read(source)).containsExactlyElementsOf(ids(expected));
    }

    @Test
    void aSpecificationSortedByAClientChosenColumn() throws IOException {
        load();
        Specification<Item> open = (root, query, builder) -> builder.equal(root.get("status"), "OPEN");
        RowSource<UUID> source = queries.specification(items, open)
                .orderBy(SPEC_SORT.resolve("amount,asc"))
                .rows(Item::getId);

        assertThat(source.customSorted()).isTrue();
        assertThat(read(source)).containsExactlyElementsOf(ids(items.findAll(open,
                Sort.by(Sort.Order.asc("amount"), Sort.Order.asc("id")))));
    }

    @Test
    void aBatchCanBeMappedAsAWhole() throws IOException {
        load();
        RowSource<String> source = queries.jpql(Item.class, "i", "from ExportItem i")
                .orderBy(JPQL_SORT.resolve(null))
                .batches(batch -> List.of("batch of " + batch.size()));

        List<String> batches = new ArrayList<>();
        source.forEachBatch(1_000, batch -> batches.addAll(batch));
        assertThat(batches).containsExactly("batch of 1000", "batch of 1000", "batch of 503");
    }

    @Test
    void anExportRefusesToRunInsideATransaction() {
        load();
        RowSource<UUID> source = queries.jpql(Item.class, "i", "from ExportItem i")
                .orderBy(JPQL_SORT.resolve(null))
                .rows(Item::getId);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> source.count()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside @Transactional");
    }

    private static List<UUID> read(RowSource<UUID> source) throws IOException {
        List<UUID> rows = new ArrayList<>();
        source.forEachBatch(BATCH, batch -> {
            assertThat(batch.size()).isLessThanOrEqualTo(BATCH);
            rows.addAll(batch);
            return true;
        });
        return rows;
    }

    private static List<UUID> ids(List<Item> list) {
        return list.stream().map(Item::getId).toList();
    }
}
