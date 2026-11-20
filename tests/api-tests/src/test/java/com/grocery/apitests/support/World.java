package com.grocery.apitests.support;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.grocery.apitests.support.Await.seconds;
import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The test data of one run, created lazily through the public APIs (the same flows as the bash smoke tests) and
 * shared by every test class: the admins, two approved grocery stores with priced and stocked products, an approved
 * restaurant open all day, an approved delivery partner, customers with an address, and orders in every state.
 *
 * <p>Everything stands in Hubballi (15.35, 75.14), away from the smoke tests' Bengaluru and Mysuru stores, so no
 * other test's delivery partner is offered our deliveries. Every name carries {@link Config#RUN}/{@link Config#TAG}.
 * Orders placed here are cancelled by an admin at the end of the run ({@link RunCleanupExtension}).
 */
public final class World {

    public static final double STORE_LAT = 15.3500;
    public static final double STORE_LNG = 75.1400;
    public static final double HOME_LAT = 15.3550;      // customers, ~0.8 km from the stores
    public static final double HOME_LNG = 75.1440;
    public static final String ADMIN_PASSWORD = "Admin1234";

    private static final Duration EVENTUAL = seconds(40);

    private static Session admin;
    private static final Map<String, Session> ADMINS = new ConcurrentHashMap<>();
    private static String category;
    private static Store storeA;
    private static Store storeB;
    private static Kitchen kitchen;
    private static Partner partner;
    private static Delivered delivered;
    private static Boolean simulator;
    private static final Set<String> PLACED_ORDERS = ConcurrentHashMap.newKeySet();

    private World() {
    }

    // ---- people ---------------------------------------------------------------------------------------------

    /** The bootstrap SUPER_ADMIN: only used to set things up (approvals, creating the other admins). */
    public static synchronized Session admin() {
        if (admin == null) {
            admin = Session.login(Config.ADMIN_EMAIL, Config.ADMIN_PASSWORD);
        }
        return admin;
    }

    /** A fresh admin user of this run with exactly one admin role (SUPER_ADMIN included). */
    public static Session adminWithRole(String role) {
        return ADMINS.computeIfAbsent(role, World::createAdmin);
    }

    public static Session createAdmin(String role) {
        String email = Session.uniqueEmail(role);
        Api.as(admin()).body(obj("email", email, "fullName", "Apit " + role.replace('_', ' '), "password", ADMIN_PASSWORD,
                "roles", List.of(role))).post("/api/admin/users").created();
        return Session.login(email, ADMIN_PASSWORD);
    }

    public record Customer(Session session, String userId, String addressId, String name) {
    }

    /** A new CUSTOMER with a default delivery address ~0.8 km from the stores. */
    public static Customer newCustomer(String label) {
        String name = "Apit " + label + " Shopper";
        Session session = Session.register("CUSTOMER", name);
        Await.status("customer profile of " + session, EVENTUAL, () -> Api.as(session).get("/api/customers/me"), 200);
        String addressId = Api.as(session).body(address(name, HOME_LAT, HOME_LNG))
                .post("/api/customers/me/addresses").created().str("id");
        return new Customer(session, session.userId(), addressId, name);
    }

    public static Map<String, Object> address(String recipient, double lat, double lng) {
        return obj("label", "Home", "recipientName", recipient, "phone", "+919876543210",
                "line1", "12 Lamington Road", "line2", "Near Clock Tower", "city", "Hubballi", "state", "Karnataka",
                "postalCode", "580020", "country", "IN", "latitude", lat, "longitude", lng);
    }

    // ---- grocery stores ---------------------------------------------------------------------------------------

    public record Product(String id, String name, BigDecimal mrp, BigDecimal price) {
    }

    public record Store(Session vendor, String vendorId, String name, Product rice, Product dal) {
    }

    public static synchronized String category() {
        if (category == null) {
            category = Api.as(admin()).body(obj("name", "Apit Staples " + Config.RUN)).post("/api/admin/categories")
                    .created().str("id");
        }
        return category;
    }

    /** The main store: rice ₹100 (MRP 120) and dal ₹150 (MRP 180), 500 of each. */
    public static synchronized Store storeA() {
        if (storeA == null) {
            storeA = newStore("Alpha", STORE_LAT, STORE_LNG);
        }
        return storeA;
    }

    /** A second, independent store (IDOR checks between vendors). */
    public static synchronized Store storeB() {
        if (storeB == null) {
            storeB = newStore("Bravo", STORE_LAT + 0.002, STORE_LNG + 0.002);
        }
        return storeB;
    }

    public static Store newStore(String label, double lat, double lng) {
        String name = "Apit " + label + " Mart " + Config.RUN;
        Session vendor = Session.register("VENDOR", "Apit " + label + " Grocer");
        String vendorId = Await.status("vendor draft of " + vendor, EVENTUAL,
                () -> Api.as(vendor).get("/api/vendors/me"), 200).str("id");
        Api.as(vendor).body(obj("businessName", name, "legalName", name + " Pvt Ltd", "gstin", "29ABCDE1234F1Z5",
                "fssaiLicense", "12345678901234", "contactEmail", "ops@" + label.toLowerCase() + ".example",
                "contactPhone", "+919876543210",
                "address", obj("line1", "1 Station Road", "city", "Hubballi", "state", "Karnataka",
                        "postalCode", "580020", "country", "IN", "latitude", lat, "longitude", lng)))
                .put("/api/vendors/me").ok();
        Api.as(vendor).post("/api/vendors/me/submit").ok();
        Api.as(admin()).post("/api/admin/vendors/" + vendorId + "/approve").ok();
        vendor.reloginUntil("vendor_id claim", s -> vendorId.equals(s.claim("vendor_id")), seconds(45));
        Product rice = createProduct(vendor, "Apit Rice " + label + " " + Config.TAG, "120.00", "100.00", 500);
        Product dal = createProduct(vendor, "Apit Dal " + label + " " + Config.TAG, "180.00", "150.00", 500);
        return new Store(vendor, vendorId, name, rice, dal);
    }

    /** Creates, publishes, prices and stocks a product, and waits until search shows it in stock with its price. */
    public static Product createProduct(Session vendor, String name, String mrp, String sellingPrice, int stock) {
        String id = Api.as(vendor).body(obj("name", name, "description", "Created by the API tests",
                "categoryId", category(), "unitValue", 1, "unitOfMeasure", "KG", "foodType", "VEG"))
                .post("/api/vendor/products").created().str("id");
        Api.as(vendor).post("/api/vendor/products/" + id + "/publish").ok();
        Await.status("pricing knows " + name, EVENTUAL, () -> Api.as(vendor).get("/api/vendor/prices/" + id), 200);
        Await.status("inventory knows " + name, EVENTUAL, () -> Api.as(vendor).get("/api/vendor/inventory/" + id), 200);
        Api.as(vendor).body(obj("mrp", new BigDecimal(mrp), "sellingPrice", new BigDecimal(sellingPrice)))
                .put("/api/vendor/prices/" + id).ok();
        if (stock > 0) {
            Api.as(vendor).body(obj("type", "SET", "quantity", stock, "reason", "STOCK_COUNT"))
                    .post("/api/vendor/inventory/" + id + "/adjustments").ok();
        }
        BigDecimal price = new BigDecimal(sellingPrice);
        Await.until(name + " searchable with price and stock", EVENTUAL, () -> Api.anon().get("/api/catalog/products/" + id),
                r -> r.status() == 200 && Boolean.valueOf(stock > 0).equals(r.bool("inStock"))
                        && r.dec("price.sellingPrice") != null && r.dec("price.sellingPrice").compareTo(price) == 0);
        return new Product(id, name, new BigDecimal(mrp), price);
    }

    // ---- restaurant ---------------------------------------------------------------------------------------------

    public record Kitchen(Session owner, String restaurantId, String name, String dosaId, String dosaName,
                          String agentDishId, String agentDishName) {
    }

    /** An approved restaurant, open 00:00–23:59 every day, with two dishes (one reserved for the assistant test). */
    public static synchronized Kitchen kitchen() {
        if (kitchen == null) {
            kitchen = newKitchen();
        }
        return kitchen;
    }

    private static Kitchen newKitchen() {
        String name = "Apit Kitchen " + Config.RUN;
        Session owner = Session.register("RESTAURANT_OWNER", "Apit Chef");
        String restaurantId = Await.status("restaurant draft of " + owner, EVENTUAL,
                () -> Api.as(owner).get("/api/restaurants/me"), 200).str("id");
        List<Object> hours = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            hours.add(obj("dayOfWeek", day, "opensAt", "00:00", "closesAt", "23:59"));
        }
        Api.as(owner).body(obj("name", name, "description", "South Indian tiffin", "cuisines", List.of("SOUTH_INDIAN"),
                "fssaiLicense", "12345678901234", "contactEmail", "kitchen@apit.example", "contactPhone", "+919876543210",
                "address", obj("line1", "3 Deshpande Nagar", "line2", "Near Clock Tower", "city", "Hubballi",
                        "state", "Karnataka", "postalCode", "580029", "country", "IN",
                        "latitude", STORE_LAT + 0.002, "longitude", STORE_LNG + 0.001),
                "openingHours", hours)).put("/api/restaurants/me").ok();
        String menuCategory = Api.as(owner).body(obj("name", "Dosa", "sortOrder", 1))
                .post("/api/restaurant/menu/categories").created().str("id");
        String dosaName = "Apit Ghee Dosa " + Config.TAG;
        String dosa = Api.as(owner).body(obj("categoryId", menuCategory, "name", dosaName, "price", new BigDecimal("120.00"),
                "foodType", "VEG")).post("/api/restaurant/menu/items").created().str("id");
        String agentDishName = "Apit Rava Dosa " + Config.TAG;
        String agentDish = Api.as(owner).body(obj("categoryId", menuCategory, "name", agentDishName,
                "price", new BigDecimal("90.00"), "foodType", "VEG")).post("/api/restaurant/menu/items").created().str("id");
        Api.as(owner).post("/api/restaurants/me/submit").ok();
        Api.as(admin()).post("/api/admin/restaurants/" + restaurantId + "/approve").ok();
        owner.reloginUntil("restaurant_id claim", s -> restaurantId.equals(s.claim("restaurant_id")), seconds(45));
        Await.status("restaurant page of " + name, EVENTUAL,
                () -> Api.anon().get("/api/catalog/restaurants/" + restaurantId), 200);
        return new Kitchen(owner, restaurantId, name, dosa, dosaName, agentDish, agentDishName);
    }

    // ---- delivery partner ---------------------------------------------------------------------------------------

    public record Partner(Session session, String partnerId) {

        public void online() {
            Api.as(session).body(obj("online", true)).put("/api/delivery/partners/me/availability").ok();
            reportLocation();
        }

        public void reportLocation() {
            Api.as(session).body(obj("latitude", STORE_LAT + 0.0005, "longitude", STORE_LNG + 0.0005,
                    "accuracyMeters", 8)).put("/api/delivery/partners/me/location");
        }

        public void offline() {
            Api.as(session).body(obj("online", false)).put("/api/delivery/partners/me/availability");
        }
    }

    /** An APPROVED delivery partner (bicycle), offline until a delivery needs it. */
    public static synchronized Partner partner() {
        if (partner == null) {
            partner = newPartner("Ravi");
        }
        return partner;
    }

    public static synchronized java.util.Optional<Partner> partnerIfCreated() {
        return java.util.Optional.ofNullable(partner);
    }

    public static Partner newPartner(String firstName) {
        Session rider = Session.register("DELIVERY_PARTNER", firstName + " Apit");
        String partnerId = Await.status("partner draft of " + rider, EVENTUAL,
                () -> Api.as(rider).get("/api/delivery/partners/me"), 200).str("id");
        Api.as(rider).body(obj("fullName", firstName + " Apit", "phone", "+919800000000", "city", "Hubballi",
                "vehicleType", "BICYCLE")).put("/api/delivery/partners/me").ok();
        Api.as(rider).post("/api/delivery/partners/me/submit").ok();
        Api.as(admin()).post("/api/admin/delivery-partners/" + partnerId + "/approve").ok();
        rider.relogin();
        Await.status("partner " + rider + " is active", EVENTUAL,
                () -> Api.as(rider).get("/api/delivery/partners/me/status"), 200);
        return new Partner(rider, partnerId);
    }

    // ---- carts and orders ---------------------------------------------------------------------------------------

    public record Order(String id, String number, BigDecimal total, Res created) {
    }

    public static void fillCart(Customer customer, Product product, int quantity) {
        Api.as(customer.session()).delete("/api/cart").expect(204);
        Api.as(customer.session()).body(obj("type", "PRODUCT", "productId", product.id(), "quantity", quantity))
                .post("/api/cart/items").ok();
    }

    public static BigDecimal previewTotal(Customer customer) {
        Res preview = Api.as(customer.session()).get("/api/cart/preview?addressId=" + customer.addressId()).ok();
        assertThat(preview.bool("canCheckout")).as("preview can check out: %s", preview.describe()).isTrue();
        return preview.dec("total");
    }

    /** Places the current cart (201), registering the order for the end-of-run cleanup. */
    public static Order place(Customer customer, String key) {
        BigDecimal total = previewTotal(customer);
        Res r = Api.as(customer.session()).idempotencyKey(key)
                .body(obj("addressId", customer.addressId(), "expectedTotal", total)).post("/api/orders");
        r.created();
        track(r.str("id"));
        return new Order(r.str("id"), r.str("orderNumber"), r.dec("total"), r);
    }

    public static Order groceryOrder(Customer customer, Product product, int quantity) {
        fillCart(customer, product, quantity);
        return place(customer, key("order"));
    }

    public static String key(String purpose) {
        return "apit-" + Config.RUN + "-" + purpose + "-" + System.nanoTime();
    }

    public static void track(String orderId) {
        if (orderId != null) {
            PLACED_ORDERS.add(orderId);
        }
    }

    public static Set<String> placedOrders() {
        return PLACED_ORDERS;
    }

    public static Res awaitOrderStatus(Customer customer, String orderId, String status) {
        return Await.field("order " + orderId + " " + status, EVENTUAL,
                () -> Api.as(customer.session()).get("/api/orders/" + orderId), "status", status);
    }

    /** Payments run against the payment simulator (local stack without Razorpay keys). */
    public static synchronized boolean simulatorPayments(Customer customer, String orderId) {
        if (simulator == null) {
            Res session = Await.status("checkout session of " + orderId, EVENTUAL,
                    () -> Api.as(customer.session()).get("/api/payments/orders/" + orderId + "/checkout"), 200);
            simulator = "SIMULATOR".equals(session.str("provider"));
        }
        return simulator;
    }

    /** Pays through the simulator (pay + verify, like the React app) and waits for CONFIRMED. */
    public static void pay(Customer customer, String orderId) {
        Await.status("checkout session of " + orderId, EVENTUAL,
                () -> Api.as(customer.session()).get("/api/payments/orders/" + orderId + "/checkout"), 200);
        assumeTrue(simulatorPayments(customer, orderId),
                "payment-service runs against real Razorpay: paying needs a browser (Razorpay Checkout)");
        Res signed = Api.as(customer.session()).body(obj("outcome", "SUCCESS"))
                .post("/api/payments/simulator/orders/" + orderId + "/pay").ok();
        Api.as(customer.session()).body(obj("razorpayOrderId", signed.str("razorpayOrderId"),
                "razorpayPaymentId", signed.str("razorpayPaymentId"),
                "razorpaySignature", signed.str("razorpaySignature"))).post("/api/payments/razorpay/verify").ok();
        awaitOrderStatus(customer, orderId, "CONFIRMED");
    }

    /** Placed, stock reserved and paid: CONFIRMED (the store has it as NEW). */
    public static Order paidGroceryOrder(Customer customer, Product product, int quantity) {
        Order order = groceryOrder(customer, product, quantity);
        awaitOrderStatus(customer, order.id(), "AWAITING_PAYMENT");
        pay(customer, order.id());
        return order;
    }

    public static Res awaitStoreOrder(Session store, String prefix, String orderId, String status) {
        return Await.field("store order " + orderId + " " + status, EVENTUAL,
                () -> Api.as(store).get(prefix + "/" + orderId), "status", status);
    }

    // ---- a delivered order -----------------------------------------------------------------------------------

    public record Delivered(Customer customer, Order order, String deliveryId, Store store, Partner partner) {
    }

    /** A grocery order (rice × 2, dal × 2 from store A) accepted, packed, picked up and DELIVERED by our partner. */
    public static synchronized Delivered deliveredOrder() {
        if (delivered == null) {
            delivered = deliver();
        }
        return delivered;
    }

    private static Delivered deliver() {
        Store store = storeA();
        Partner rider = partner();
        Customer customer = newCustomer("Delivered");
        Api.as(customer.session()).delete("/api/cart").expect(204);
        Api.as(customer.session()).body(obj("type", "PRODUCT", "productId", store.rice().id(), "quantity", 2))
                .post("/api/cart/items").ok();
        Api.as(customer.session()).body(obj("type", "PRODUCT", "productId", store.dal().id(), "quantity", 2))
                .post("/api/cart/items").ok();
        Order order = place(customer, key("delivered"));
        awaitOrderStatus(customer, order.id(), "AWAITING_PAYMENT");
        pay(customer, order.id());
        rider.online();
        awaitStoreOrder(store.vendor(), "/api/vendor/orders", order.id(), "NEW");
        Api.as(store.vendor()).body(obj("readyInMinutes", 10)).post("/api/vendor/orders/" + order.id() + "/accept").ok();
        Api.as(store.vendor()).post("/api/vendor/orders/" + order.id() + "/ready").ok();
        String deliveryId = assign(rider, order);
        Await.status("pickup of " + order.number(), EVENTUAL,
                () -> Api.as(rider.session()).post("/api/delivery/deliveries/" + deliveryId + "/pickup"), 200);
        rider.reportLocation();
        Api.as(rider.session()).post("/api/delivery/deliveries/" + deliveryId + "/deliver").ok();
        awaitOrderStatus(customer, order.id(), "DELIVERED");
        rider.offline();
        return new Delivered(customer, order, deliveryId, store, rider);
    }

    private static String deliveredReview;
    private static String deliveredReturn;

    /** The delivered customer's 5★ review of the rice (PUBLISHED). */
    public static synchronized String deliveredReview() {
        if (deliveredReview == null) {
            Delivered d = deliveredOrder();
            deliveredReview = Api.as(d.customer().session()).body(obj("orderId", d.order().id(), "targetType", "PRODUCT",
                    "targetId", d.store().rice().id(), "rating", 5, "title", "Great rice",
                    "comment", "Fresh and well packed.")).post("/api/reviews").created().str("id");
        }
        return deliveredReview;
    }

    /** The delivered customer's return request for 1 of the 2 kg of rice (REQUESTED when created). */
    public static synchronized String deliveredReturn() {
        if (deliveredReturn == null) {
            Delivered d = deliveredOrder();
            deliveredReturn = Api.as(d.customer().session()).body(obj("items", List.of(obj("itemId", d.store().rice().id(),
                    "quantity", 1)), "reason", "DAMAGED", "note", "Torn packet"))
                    .post("/api/orders/" + d.order().id() + "/returns").created().str("id");
        }
        return deliveredReturn;
    }

    /** Waits for our partner's offer and accepts it; falls back to an admin assignment if the offer goes elsewhere. */
    public static String assign(Partner rider, Order order) {
        long start = System.currentTimeMillis();
        long deadline = start + Duration.ofSeconds(120).toMillis();
        String lastSeen = "";
        while (System.currentTimeMillis() < deadline) {
            rider.reportLocation();
            Res offers = Api.as(rider.session()).get("/api/delivery/offers");
            if (offers.status() == 200) {
                for (Map<String, Object> offer : offers.<Map<String, Object>>list("$")) {
                    if (order.number().equals(offer.get("orderNumber"))) {
                        Res accepted = Api.as(rider.session()).post("/api/delivery/offers/" + offer.get("offerId") + "/accept");
                        if (accepted.status() == 200) {
                            return accepted.str("deliveryId");
                        }
                    }
                }
            }
            if (System.currentTimeMillis() - start > 12_000) {
                Res deliveries = Api.as(admin()).get("/api/admin/deliveries?q=" + order.number());
                List<Map<String, Object>> content = deliveries.status() == 200 ? deliveries.list("content") : List.of();
                if (!content.isEmpty()) {
                    Map<String, Object> delivery = content.getFirst();
                    lastSeen = String.valueOf(delivery);
                    String deliveryId = String.valueOf(delivery.get("deliveryId"));
                    if ("ASSIGNED".equals(delivery.get("status"))
                            && rider.partnerId().equals(Objects.toString(delivery.get("partnerId"), null))) {
                        return deliveryId;
                    }
                    if ("SEARCHING".equals(delivery.get("status")) || "ASSIGNED".equals(delivery.get("status"))) {
                        Res reassigned = Api.as(admin()).body(obj("partnerId", rider.partnerId(),
                                "reason", "API tests: assign our partner")).post("/api/admin/deliveries/" + deliveryId + "/reassign");
                        if (reassigned.status() == 200) {
                            return deliveryId;
                        }
                    }
                }
            }
            Api.sleep(1500);
        }
        throw new AssertionError("No delivery assignment for " + order.number() + " within 120 s; last: " + lastSeen);
    }
}
