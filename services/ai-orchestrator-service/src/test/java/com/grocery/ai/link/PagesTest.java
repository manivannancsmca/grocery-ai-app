package com.grocery.ai.link;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagesTest {

    @Test
    void aProductSearchLinksToTheSearchPageWithTheSameFilters() {
        assertThat(Pages.productSearch("basmati rice", null, null, null))
                .isEqualTo(new PageLink("See all results for \"basmati rice\"", "/search?q=basmati+rice"));
        assertThat(Pages.productSearch(null, null, new BigDecimal("50.00"), "PRICE_ASC"))
                .isEqualTo(new PageLink("See all products up to ₹50.00", "/search?maxPrice=50&sort=PRICE_ASC"));
        assertThat(Pages.productSearch("paneer", new BigDecimal("10"), new BigDecimal("200.50"), "RELEVANCE").path())
                .isEqualTo("/search?q=paneer&minPrice=10&maxPrice=200.5");    // relevance is the page's default
    }

    @Test
    void queryValuesAreEncodedSoTheyCannotChangeThePath() {
        assertThat(Pages.productSearch("rice & dal?#x", null, null, null).path())
                .isEqualTo("/search?q=rice+%26+dal%3F%23x");
        assertThat(Pages.productSearch("c++", null, null, null).path()).isEqualTo("/search?q=c%2B%2B");
        assertThat(Pages.vendorInventory("LOW_STOCK", "Toor Dal").path())
                .isEqualTo("/vendor/inventory?q=Toor+Dal&filter=LOW_STOCK");
    }

    @Test
    void ordersLinkByTheirIdAndNameTheirNumber() {
        UUID id = UUID.fromString("01a1d3de-9cc4-7074-b1f3-2dd6a0a603ca");

        assertThat(Pages.myOrder(id, "GA-7K3F9Q2M"))
                .isEqualTo(new PageLink("Track order GA-7K3F9Q2M", "/orders/" + id));
        assertThat(Pages.adminOrder(id, "GA-7K3F9Q2M"))
                .isEqualTo(new PageLink("Open order GA-7K3F9Q2M", "/admin/orders?order=" + id));
        assertThat(Pages.myOrders(null).path()).isEqualTo("/orders");
        assertThat(Pages.myOrders("DELIVERED").path()).isEqualTo("/orders?status=DELIVERED");
    }

    @Test
    void theDeliveryMonitorKeepsItsFilters() {
        assertThat(Pages.deliveryMonitor(null, true).path()).isEqualTo("/admin/deliveries?noPartner=1");
        assertThat(Pages.deliveryMonitor("PICKED_UP", false).path()).isEqualTo("/admin/deliveries?status=PICKED_UP");
        assertThat(Pages.delivery("GA-7K3F9Q2M").path()).isEqualTo("/admin/deliveries?q=GA-7K3F9Q2M");
    }

    @Test
    void theLinkRuleListsTheAgentsPagesForTheModel() {
        String rule = Pages.linkRule(List.of(Pages.cart(), new PageLink("My orders", "/orders")));

        assertThat(rule).contains("[View my cart](/cart), [My orders](/orders)").contains("Never invent other links");
    }

    @Test
    void everyLinkIsAnAppPath() {
        assertThatThrownBy(() -> new PageLink("Evil", "https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageLink("Evil", "//example.com/x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(Pages.SHOPPING_PAGES, Pages.ADMIN_PAGES, Pages.VENDOR_PAGES, Pages.RESTAURANT_PAGES,
                Pages.DELIVERY_PAGES)).allSatisfy(pages -> assertThat(pages).isNotEmpty()
                .allSatisfy(page -> assertThat(page.path()).startsWith("/")));
    }
}
