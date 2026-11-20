package com.grocery.catalogquery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event payloads exactly as the producers publish them (contract §8), as mutable maps tests can tweak. */
final class TestEvents {

    record Cat(String id, String name) {
        static Cat of(String name) {
            return new Cat(UUID.randomUUID().toString(), name);
        }
    }

    private TestEvents() {
    }

    /** ProductUpserted, PUBLISHED and visible, in the leaf category {@code path.getLast()}. */
    static Map<String, Object> product(String productId, String vendorId, String name, long version, List<Cat> path) {
        Cat leaf = path.getLast();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("productId", productId);
        p.put("vendorId", vendorId);
        p.put("vendorName", "Green Basket");
        p.put("vendorActive", true);
        p.put("name", name);
        p.put("description", "Fresh " + name.toLowerCase() + " from local farms");
        p.put("brand", ref("b0000000-0000-0000-0000-000000000001", "Farm Fresh"));
        p.put("category", ref(leaf.id(), leaf.name()));
        p.put("categoryPath", path.stream().map(c -> ref(c.id(), c.name())).toList());
        p.put("categoryActive", true);
        p.put("unitValue", 500);
        p.put("unitOfMeasure", "G");
        p.put("unitLabel", "500 g");
        p.put("sku", "SKU-" + version);
        p.put("barcode", null);
        p.put("foodType", "VEG");
        p.put("tags", List.of("organic"));
        p.put("attributes", List.of(Map.of("name", "Origin", "value", "Nashik")));
        p.put("imageUrls", List.of("https://img.example.com/" + productId + ".jpg"));
        p.put("status", "PUBLISHED");
        p.put("publishedAt", "2026-11-11T10:15:30Z");
        p.put("updatedAt", "2026-11-11T10:15:30.123456Z");
        p.put("productVersion", version);
        return p;
    }

    /** PriceChanged as pricing-service publishes it (money = JSON numbers with 2 decimals). */
    static Map<String, Object> price(String productId, String vendorId, String mrp, String sellingPrice, long version) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("productId", productId);
        p.put("vendorId", vendorId);
        p.put("mrp", new BigDecimal(mrp));
        p.put("sellingPrice", new BigDecimal(sellingPrice));
        p.put("currency", "INR");
        p.put("priceVersion", version);
        p.put("changedAt", "2026-11-11T10:20:00Z");
        return p;
    }

    /**
     * RatingSummaryUpdated as review-service publishes it (schema 1.5.0): {@code distribution} = published ratings
     * per star, index 0 = 1 star; count and average (2 decimals, half up) follow from it.
     */
    static Map<String, Object> ratingSummary(String targetType, String targetId, long version, int... distribution) {
        int count = 0;
        int sum = 0;
        List<Integer> stars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i < distribution.length ? distribution[i] : 0;
            stars.add(n);
            count += n;
            sum += (i + 1) * n;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("targetType", targetType);
        s.put("targetId", targetId);
        s.put("averageRating", count == 0 ? new BigDecimal("0.00")
                : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, java.math.RoundingMode.HALF_UP));
        s.put("ratingCount", count);
        s.put("ratingDistribution", stars);
        s.put("summaryVersion", version);
        s.put("updatedAt", "2026-11-13T10:15:30.123456Z");
        return s;
    }

    /** ProductAvailabilityChanged as inventory-service publishes it. */
    static Map<String, Object> availability(String productId, String vendorId, boolean inStock, long version) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("productId", productId);
        s.put("vendorId", vendorId);
        s.put("inStock", inStock);
        s.put("stockVersion", version);
        return s;
    }

    /** CategoryChanged for the last entry of {@code path} (root → this category), active. */
    static Map<String, Object> category(List<Cat> path, int sortOrder, long version) {
        Cat self = path.getLast();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("categoryId", self.id());
        c.put("parentId", path.size() > 1 ? path.get(path.size() - 2).id() : null);
        c.put("name", self.name());
        c.put("description", null);
        c.put("imageUrl", null);
        c.put("sortOrder", sortOrder);
        c.put("depth", path.size());
        c.put("path", path.stream().map(x -> ref(x.id(), x.name())).toList());
        c.put("active", true);
        c.put("effectiveActive", true);
        c.put("deleted", false);
        c.put("categoryVersion", version);
        return c;
    }

    /** RestaurantListingUpdated, APPROVED, open every day 09:00–23:00, with one menu category. */
    static Map<String, Object> restaurant(String restaurantId, String name, long version, Double lat, Double lng,
                                          List<Map<String, Object>> items) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("restaurantId", restaurantId);
        r.put("ownerUserId", UUID.randomUUID().toString());
        r.put("status", "APPROVED");
        r.put("name", name);
        r.put("description", "Home-style cooking");
        r.put("cuisines", List.of("NORTH_INDIAN"));
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("line1", "12 MG Road");
        address.put("line2", "Indiranagar");
        address.put("city", "Bengaluru");
        address.put("state", "Karnataka");
        address.put("postalCode", "560038");
        address.put("country", "IN");
        address.put("latitude", lat);
        address.put("longitude", lng);
        r.put("address", address);
        List<Map<String, Object>> hours = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            hours.add(hours(day, "09:00", "23:00"));
        }
        r.put("openingHours", hours);
        r.put("acceptingOrders", true);
        List<Map<String, Object>> menu = new ArrayList<>();
        menu.add(menuCategory("Mains", 0, items));
        r.put("menu", menu);
        r.put("listingVersion", version);
        r.put("updatedAt", "2026-11-11T10:15:30Z");
        return r;
    }

    static Map<String, Object> hours(String day, String opensAt, String closesAt) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("dayOfWeek", day);
        h.put("opensAt", opensAt);
        h.put("closesAt", closesAt);
        return h;
    }

    static Map<String, Object> menuCategory(String name, int sortOrder, List<Map<String, Object>> items) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("categoryId", UUID.randomUUID().toString());
        c.put("name", name);
        c.put("description", null);
        c.put("sortOrder", sortOrder);
        c.put("items", items);
        return c;
    }

    static Map<String, Object> item(String name, String price, String foodType) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("itemId", UUID.randomUUID().toString());
        i.put("name", name);
        i.put("description", name + ", freshly made");
        i.put("price", new BigDecimal(price));
        i.put("foodType", foodType);
        i.put("imageUrl", null);
        i.put("available", true);
        i.put("sortOrder", 0);
        return i;
    }

    private static Map<String, Object> ref(String id, String name) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", id);
        ref.put("name", name);
        return ref;
    }
}
