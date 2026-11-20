package com.grocery.common.export;

import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.web.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The sort whitelist and the keyset query text (the part of an export that must never mix up rows). */
class ExportSortTest {

    record Item(UUID id, Instant createdAt, String name) {
    }

    static final ExportSort<Item> SORT = ExportSort.<Item>by(
                    Key.desc("i.createdAt", Item::createdAt), Key.desc("i.id", Item::id))
            .option("name", "i.name", Item::name)
            .build();

    @Test
    void noSortMeansTheListsOwnOrder() {
        Order<Item> order = SORT.resolve(" ");
        assertThat(order.custom()).isFalse();
        assertThat(order.keys()).extracting(Key::path).containsExactly("i.createdAt", "i.id");
    }

    @Test
    void aChosenColumnComesFirstAndTheIdBreaksTies() {
        Order<Item> order = SORT.resolve("name,desc");
        assertThat(order.custom()).isTrue();
        assertThat(order.keys()).extracting(Key::path).containsExactly("i.name", "i.id");
        assertThat(order.keys()).extracting(Key::descending).containsExactly(true, true);
        assertThat(SORT.resolve("name").keys()).extracting(Key::descending).containsExactly(false, false);
    }

    @Test
    void onlyWhitelistedColumnsAndDirections() {
        assertThatThrownBy(() -> SORT.resolve("passwordHash,asc"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot be sorted by 'passwordHash'");
        assertThatThrownBy(() -> SORT.resolve("name,sideways")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> SORT.resolve("name,asc,extra")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theBoundaryKeepsTheListsFiltersInTheirOwnParentheses() {
        ExportQueries queries = new ExportQueries(null, null);
        ExportQueries.JpqlRows<Item> rows = queries.jpql(Item.class, "i",
                "from Item i where :q is null or lower(i.name) like :q");

        assertThat(rows.text(SORT.resolve(null), false)).isEqualTo(
                "select i from Item i where (:q is null or lower(i.name) like :q) order by i.createdAt desc, i.id desc");
        assertThat(rows.text(SORT.resolve(null), true)).isEqualTo(
                "select i from Item i where (i.createdAt <= :exportAfter0 and (i.createdAt < :exportAfter0 or "
                        + "(i.createdAt = :exportAfter0 and i.id < :exportAfter1))) "
                        + "and (:q is null or lower(i.name) like :q) order by i.createdAt desc, i.id desc");
    }

    @Test
    void aQueryWithoutFiltersGetsJustTheBoundary() {
        ExportQueries.JpqlRows<Item> rows = new ExportQueries(null, null).jpql(Item.class, "i", "from Item i");
        assertThat(rows.text(SORT.resolve("name,asc"), true)).isEqualTo(
                "select i from Item i where (i.name >= :exportAfter0 and (i.name > :exportAfter0 or "
                        + "(i.name = :exportAfter0 and i.id > :exportAfter1))) order by i.name asc, i.id asc");
    }

    @Test
    void theListsOwnOrderByIsRefused() {
        assertThatThrownBy(() -> new ExportQueries(null, null).jpql(Item.class, "i",
                "from Item i where i.name = :n order by i.name"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
