package com.grocery.catalog.entity;

import com.grocery.common.web.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductRulesTest {

    private final Instant t0 = Instant.parse("2026-11-11T10:00:00Z");

    private static Product.Details details(String name) {
        return new Product.Details(UUID.randomUUID(), null, name, null, new BigDecimal("500.000"), UnitOfMeasure.G,
                null, null, null, List.of(), List.of(), List.of());
    }

    @Test
    void unitLabelsStripTrailingZeros() {
        assertThat(UnitOfMeasure.G.label(new BigDecimal("500.000"))).isEqualTo("500 g");
        assertThat(UnitOfMeasure.KG.label(new BigDecimal("1.500"))).isEqualTo("1.5 kg");
        assertThat(UnitOfMeasure.L.label(new BigDecimal("1"))).isEqualTo("1 L");
        assertThat(UnitOfMeasure.ML.label(new BigDecimal("250.0"))).isEqualTo("250 ml");
        assertThat(UnitOfMeasure.PCS.label(new BigDecimal("6"))).isEqualTo("6 pcs");
        assertThat(UnitOfMeasure.PACK.label(new BigDecimal("2.000"))).isEqualTo("2 pack");
        assertThat(UnitOfMeasure.plain(new BigDecimal("100000.000")).toString()).isEqualTo("100000");
        assertThat(UnitOfMeasure.plain(new BigDecimal("0.125")).toString()).isEqualTo("0.125");
    }

    @Test
    void statusMachine() {
        Product product = Product.draft(UUID.randomUUID(), UUID.randomUUID(), details("Tomato"), t0);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);

        assertThatThrownBy(() -> product.unpublish(t0))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode()).isEqualTo("INVALID_STATUS_TRANSITION");
        assertThatThrownBy(() -> product.publish(false, t0))
                .extracting(e -> ((ConflictException) e).errorCode()).isEqualTo("CATEGORY_INACTIVE");

        product.publish(true, t0.plusSeconds(1));
        assertThat(product.getPublishedAt()).isEqualTo(t0.plusSeconds(1));
        assertThatThrownBy(() -> product.publish(true, t0))
                .extracting(e -> ((ConflictException) e).errorCode()).isEqualTo("INVALID_STATUS_TRANSITION");
        product.unpublish(t0.plusSeconds(2));
        product.publish(true, t0.plusSeconds(3));
        assertThat(product.getPublishedAt()).isEqualTo(t0.plusSeconds(1));      // first publish only

        product.archive(t0.plusSeconds(4));
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ARCHIVED);
        assertThat(product.getArchivedAt()).isEqualTo(t0.plusSeconds(4));
        assertThatThrownBy(() -> product.update(details("Edited"), t0))
                .extracting(e -> ((ConflictException) e).errorCode()).isEqualTo("PRODUCT_ARCHIVED");
        assertThatThrownBy(() -> product.archive(t0))
                .extracting(e -> ((ConflictException) e).errorCode()).isEqualTo("INVALID_STATUS_TRANSITION");
        assertThatThrownBy(() -> product.publish(true, t0))
                .extracting(e -> ((ConflictException) e).errorCode()).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    void versionsOnlyGoUp() {
        Product product = Product.draft(UUID.randomUUID(), UUID.randomUUID(), details("Tomato"), t0);
        assertThat(product.nextVersion()).isEqualTo(1);
        assertThat(product.nextVersion()).isEqualTo(2);
    }

    @Test
    void brandKeysIgnoreCaseSpacingPunctuationAndAccents() {
        assertThat(Brand.nameKey("Farm Fresh")).isEqualTo("farm fresh");
        assertThat(Brand.nameKey("  farm   FRESH ")).isEqualTo("farm fresh");
        assertThat(Brand.nameKey("Amul's")).isEqualTo("amuls");
        assertThat(Brand.nameKey("A & B Foods")).isEqualTo("a b foods");
        assertThat(Brand.nameKey("Café Noir")).isEqualTo("cafe noir");
        assertThat(Brand.nameKey("अमूल")).isEqualTo("अमूल");
        assertThat(Brand.displayName("  Farm   Fresh ")).isEqualTo("Farm Fresh");
    }
}
