package com.grocery.catalogquery.service;

import com.grocery.catalogquery.document.ProductDocument;
import com.grocery.catalogquery.document.RestaurantDocument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriceAndStockIndexerTest {

    private static int discount(String mrp, String sellingPrice) {
        return PriceAndStockIndexer.discountPercent(new BigDecimal(mrp), new BigDecimal(sellingPrice));
    }

    @Test
    void discountIsTheWholePercentOffTheMrp() {
        assertThat(discount("60.00", "49.00")).isEqualTo(18);       // 18.33 → 18
        assertThat(discount("200.00", "149.00")).isEqualTo(26);     // 25.5 → 26 (half up)
        assertThat(discount("100.00", "100.00")).isZero();
        assertThat(discount("100.00", "99.99")).isZero();           // 0.01 % rounds to 0
        assertThat(discount("1000000.00", "1.00")).isEqualTo(99);   // never shown as 100 % off
        assertThat(discount("50.00", "60.00")).isZero();            // selling above MRP: no discount
    }

    @Test
    void theFourPartsOfAProductDocumentDoNotShareFields() {
        assertThat(ProductDocument.CATALOG_FIELDS)
                .contains("productId", "name", "visible", "productVersion", "brandId", "categoryIds")
                .doesNotContainAnyElementsOf(ProductDocument.PRICE_FIELDS)
                .doesNotContainAnyElementsOf(ProductDocument.STOCK_FIELDS)
                .doesNotContainAnyElementsOf(ProductDocument.RATING_FIELDS);
        assertThat(ProductDocument.PRICE_FIELDS).doesNotContainAnyElementsOf(ProductDocument.STOCK_FIELDS)
                .doesNotContainAnyElementsOf(ProductDocument.RATING_FIELDS);
        assertThat(ProductDocument.STOCK_FIELDS).doesNotContainAnyElementsOf(ProductDocument.RATING_FIELDS);
        assertThat(ProductDocument.RATING_FIELDS).containsExactly("rating", "ratingCount", "ratingVersion");
        assertThat(ProductDocument.CATALOG_FIELDS.size() + ProductDocument.PRICE_FIELDS.size()
                + ProductDocument.STOCK_FIELDS.size() + ProductDocument.RATING_FIELDS.size())
                .isEqualTo(ProductDocument.class.getRecordComponents().length);
    }

    @Test
    void theTwoPartsOfARestaurantDocumentDoNotShareFields() {
        assertThat(RestaurantDocument.LISTING_FIELDS)
                .contains("restaurantId", "name", "visible", "listingVersion", "menu", "menuItems")
                .doesNotContainAnyElementsOf(RestaurantDocument.RATING_FIELDS);
        assertThat(RestaurantDocument.RATING_FIELDS).containsExactly("rating", "ratingCount", "ratingVersion");
        assertThat(RestaurantDocument.LISTING_FIELDS.size() + RestaurantDocument.RATING_FIELDS.size())
                .isEqualTo(RestaurantDocument.class.getRecordComponents().length);
    }
}
