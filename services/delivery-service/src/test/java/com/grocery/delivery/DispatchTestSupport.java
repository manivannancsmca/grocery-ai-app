package com.grocery.delivery;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.delivery.dispatch.config.DispatchProperties;
import com.grocery.delivery.dispatch.dto.DispatchDtos.LocationRequest;
import com.grocery.delivery.dispatch.event.OrderEventsListener;
import com.grocery.delivery.dispatch.geo.GeoPoint;
import com.grocery.delivery.dispatch.geo.PartnerGeoIndex;
import com.grocery.delivery.dispatch.service.AssignmentEngine;
import com.grocery.delivery.dispatch.service.AvailabilityService;
import com.grocery.delivery.dispatch.service.DeliveryAdminService;
import com.grocery.delivery.dispatch.service.DispatchPoller;
import com.grocery.delivery.dispatch.service.OfferService;
import com.grocery.delivery.dispatch.service.PartnerDeliveryService;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerUpdateRequest;
import com.grocery.delivery.partner.service.PartnerAdminService;
import com.grocery.delivery.partner.service.PartnerOnboardingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Helpers for the dispatch tests: approved partners online at given places, order.events records handed to the Kafka
 * listener the way Debezium delivers them (routing data in the headers), the poller, the outbox and the database.
 *
 * <p>Isolation: the database and Redis are shared by all test classes, so before each test every open delivery is
 * cancelled, every partner goes offline and the GEO set is emptied; each test then works around its own random point
 * on the globe ({@link #base}), so nothing of another test is ever within 10 km of it.
 */
public abstract class DispatchTestSupport extends AbstractIntegrationTest {

    protected static final String PARTNER = "DELIVERY_PARTNER";
    protected static final String CUSTOMER = "CUSTOMER";
    protected static final String AGGREGATE = "Delivery";
    /** Degrees of latitude per km for the haversine's earth (R = 6371 km). */
    protected static final double KM_PER_DEGREE = 6371.0 * Math.PI / 180.0;
    /** 12:00 India time. */
    protected static final Instant T0 = Instant.parse("2026-11-10T06:30:00Z");

    @Autowired
    protected OrderEventsListener orderListener;
    @Autowired
    protected PartnerOnboardingService onboarding;
    @Autowired
    protected PartnerAdminService partnerAdmin;
    @Autowired
    protected AvailabilityService availability;
    @Autowired
    protected OfferService offers;
    @Autowired
    protected PartnerDeliveryService partnerDeliveries;
    @Autowired
    protected DeliveryAdminService deliveryAdmin;
    @Autowired
    protected DispatchPoller poller;
    @Autowired
    protected AssignmentEngine engine;
    @Autowired
    protected DispatchProperties dispatch;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected StringRedisTemplate redis;

    /** This test's store location: a random point on the globe. */
    protected GeoPoint base;
    protected final UUID admin = UUID.randomUUID();

    @BeforeEach
    void isolateDispatch() {
        jdbc().sql("UPDATE delivery_offer SET status = 'WITHDRAWN', closed_at = UTC_TIMESTAMP(6) WHERE status = 'PENDING'")
                .update();
        jdbc().sql("UPDATE delivery SET status = 'CANCELLED', next_search_at = NULL "
                + "WHERE status IN ('PENDING', 'SEARCHING', 'ASSIGNED', 'PICKED_UP')").update();
        jdbc().sql("UPDATE delivery_partner SET online = FALSE, active_delivery_id = NULL").update();
        redis.delete(PartnerGeoIndex.KEY);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        base = new GeoPoint(random.nextDouble(-55, 55), random.nextDouble(-170, 170));
        clock.set(T0);
    }

    // ---- geography ----------------------------------------------------------------------------------------------

    /** The point {@code kmNorth} north and {@code kmEast} east of {@link #base} (straight line = the haversine). */
    protected GeoPoint at(double kmNorth, double kmEast) {
        double latitude = base.latitude() + kmNorth / KM_PER_DEGREE;
        double longitude = base.longitude()
                + kmEast / (KM_PER_DEGREE * Math.cos(Math.toRadians(latitude)));
        return new GeoPoint(latitude, longitude);
    }

    // ---- partners -----------------------------------------------------------------------------------------------

    protected static CurrentUser adminUser(UUID adminId) {
        return new CurrentUser(adminId, "admin@example.com", "Admin", Set.of("DELIVERY_MANAGER"), null, null);
    }

    /** A partner registered, submitted and approved (SCOOTER), offline, without a location. */
    protected UUID approvedPartner(String fullName) {
        UUID user = UUID.randomUUID();
        CurrentUser me = new CurrentUser(user, "rider-" + user + "@example.com", fullName, Set.of(PARTNER), null, null);
        onboarding.updateMyProfile(me, new PartnerUpdateRequest(fullName, "+919800000000", "Bengaluru", "SCOOTER",
                "KA01AB1234", "KA0120201234567"));
        onboarding.submitMyProfile(me);
        partnerAdmin.approve(user, adminUser(admin));
        return user;
    }

    /** An approved partner, online, who just reported being at {@code point}. */
    protected UUID onlinePartnerAt(String fullName, GeoPoint point) {
        UUID partner = approvedPartner(fullName);
        availability.setOnline(partner, true);
        report(partner, point);
        return partner;
    }

    protected void report(UUID partner, GeoPoint point) {
        availability.reportLocation(partner, new LocationRequest(point.latitude(), point.longitude(), 8.0, 90.0, 18.0));
    }

    // ---- order.events -------------------------------------------------------------------------------------------

    /** An order as order-service knows it. */
    protected record Order(UUID orderId, String orderNumber, UUID customerId, UUID storeId) {
    }

    protected static String newEventId() {
        return Ids.newId().toString();
    }

    protected static String orderNumber(UUID orderId) {
        return "GA-" + orderId.toString().replace("-", "").substring(24).toUpperCase();
    }

    /** A record as Debezium's outbox router delivers it: routing data in the headers, the payload as the value. */
    protected ConsumerRecord<String, String> orderEvent(String eventType, String eventId, UUID orderId, int version,
                                                        Map<String, Object> payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0L, orderId.toString(),
                json.writeValueAsString(payload));
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", Integer.toString(version).getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", "Order".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /**
     * OrderConfirmed v3 of a GROCERY order: 3 units, total 245.00, the address of "Ravi Kumar" (landmark "Near Metro",
     * line 2 Indiranagar, Bengaluru) at {@code drop}; {@code store} null = an older event without storeLocation.
     */
    protected Map<String, Object> confirmedPayload(Order order, GeoPoint store, GeoPoint drop) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("label", "Home");
        address.put("recipientName", "Ravi Kumar");
        address.put("phone", "+919876543210");
        address.put("line1", "42 HAL 2nd Stage");
        address.put("line2", "Indiranagar");
        address.put("landmark", "Near Metro");
        address.put("city", "Bengaluru");
        address.put("state", "Karnataka");
        address.put("postalCode", "560038");
        address.put("latitude", drop == null ? null : drop.latitude());
        address.put("longitude", drop == null ? null : drop.longitude());
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("itemId", UUID.randomUUID().toString());
        item1.put("type", "PRODUCT");
        item1.put("name", "Basmati Rice");
        item1.put("quantity", 2);
        item1.put("unitPrice", new BigDecimal("50.00"));
        item1.put("lineTotal", new BigDecimal("100.00"));
        Map<String, Object> item2 = new LinkedHashMap<>(item1);
        item2.put("itemId", UUID.randomUUID().toString());
        item2.put("name", "Toor Dal");
        item2.put("quantity", 1);
        item2.put("unitPrice", new BigDecimal("120.00"));
        item2.put("lineTotal", new BigDecimal("120.00"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId().toString());
        payload.put("orderNumber", order.orderNumber());
        payload.put("orderType", "GROCERY");
        payload.put("customerId", order.customerId().toString());
        payload.put("storeId", order.storeId().toString());
        payload.put("items", List.of(item1, item2));
        payload.put("total", new BigDecimal("245.00"));
        payload.put("deliveryAddress", address);
        payload.put("confirmedAt", "2026-11-10T06:00:00.123456Z");
        payload.put("subtotal", new BigDecimal("220.00"));
        payload.put("note", "Ring twice");
        if (store != null) {
            Map<String, Object> storeLocation = new LinkedHashMap<>();
            storeLocation.put("name", "Green Basket");
            storeLocation.put("city", "Bengaluru");
            storeLocation.put("latitude", store.latitude());
            storeLocation.put("longitude", store.longitude());
            payload.put("storeLocation", storeLocation);
        }
        return payload;
    }

    protected Order newOrder() {
        UUID orderId = Ids.newId();
        return new Order(orderId, orderNumber(orderId), UUID.randomUUID(), UUID.randomUUID());
    }

    /** A paid order picked up at {@link #base}, delivered 3 km east of it: a PENDING delivery. */
    protected Order confirmedOrder() {
        Order order = newOrder();
        confirm(order, base, at(0, 3));
        return order;
    }

    protected void confirm(Order order, GeoPoint store, GeoPoint drop) {
        orderListener.on(orderEvent("OrderConfirmed", newEventId(), order.orderId(), 1,
                confirmedPayload(order, store, drop)));
    }

    /** The store accepted it: SEARCHING and the first wave. */
    protected void storeAccepted(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId().toString());
        payload.put("orderNumber", order.orderNumber());
        payload.put("orderType", "GROCERY");
        payload.put("customerId", order.customerId().toString());
        payload.put("storeId", order.storeId().toString());
        payload.put("readyInMinutes", 15);
        payload.put("readyBy", clock.instant().plusSeconds(900).toString());
        payload.put("acceptedAt", clock.instant().toString());
        orderListener.on(orderEvent("OrderAccepted", newEventId(), order.orderId(), 1, payload));
    }

    protected void readyForPickup(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId().toString());
        payload.put("orderNumber", order.orderNumber());
        payload.put("orderType", "GROCERY");
        payload.put("customerId", order.customerId().toString());
        payload.put("storeId", order.storeId().toString());
        payload.put("readyAt", clock.instant().toString());
        orderListener.on(orderEvent("OrderReadyForPickup", newEventId(), order.orderId(), 1, payload));
    }

    protected void orderCancelled(Order order, String previousStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId().toString());
        payload.put("orderNumber", order.orderNumber());
        payload.put("orderType", "GROCERY");
        payload.put("customerId", order.customerId().toString());
        payload.put("storeId", order.storeId().toString());
        payload.put("previousStatus", previousStatus);
        payload.put("reason", "ADMIN_CANCELLED");
        payload.put("cancelledBy", "ADMIN");
        payload.put("actorUserId", UUID.randomUUID().toString());
        payload.put("note", "Customer called support");
        payload.put("cancelledAt", clock.instant().toString());
        orderListener.on(orderEvent("OrderCancelled", newEventId(), order.orderId(), 1, payload));
    }

    /** A paid order the store accepted: SEARCHING, the first wave already sent. */
    protected Order searchingOrder() {
        Order order = confirmedOrder();
        storeAccepted(order);
        return order;
    }

    // ---- database -----------------------------------------------------------------------------------------------

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    protected UUID deliveryId(Order order) {
        return UUID.fromString(jdbc().sql("SELECT BIN_TO_UUID(id) FROM delivery WHERE order_id = UUID_TO_BIN(?)")
                .param(order.orderId().toString()).query(String.class).single());
    }

    /** The delivery row of the order (UUID columns as text). */
    protected Map<String, Object> delivery(Order order) {
        return jdbc().sql("""
                        SELECT BIN_TO_UUID(id) AS id, status, BIN_TO_UUID(partner_id) AS partner_id, ready_for_pickup,
                               assignment_failed, search_round, search_radius_index, search_wave, offers_sent,
                               next_search_at
                        FROM delivery WHERE order_id = UUID_TO_BIN(?)""")
                .param(order.orderId().toString()).query().singleRow();
    }

    protected String deliveryStatus(Order order) {
        return (String) delivery(order).get("status");
    }

    /** One offer as stored. */
    protected record OfferRow(UUID offerId, UUID partnerId, String status, double distanceKm, int round, int wave) {
    }

    /** Every offer of the order's delivery, in the order they were sent (nearest first within a wave). */
    protected List<OfferRow> offerRows(Order order) {
        return jdbc().sql("""
                        SELECT BIN_TO_UUID(o.id) AS offer_id, BIN_TO_UUID(o.partner_id) AS partner_id, o.status,
                               o.distance_km, o.search_round, o.wave
                        FROM delivery_offer o JOIN delivery d ON d.id = o.delivery_id
                        WHERE d.order_id = UUID_TO_BIN(?)
                        ORDER BY o.sent_at, o.wave, o.distance_km""")
                .param(order.orderId().toString())
                .query((rs, n) -> new OfferRow(UUID.fromString(rs.getString("offer_id")),
                        UUID.fromString(rs.getString("partner_id")), rs.getString("status"),
                        rs.getDouble("distance_km"), rs.getInt("search_round"), rs.getInt("wave")))
                .list();
    }

    protected List<UUID> offeredPartners(Order order) {
        return offerRows(order).stream().map(OfferRow::partnerId).toList();
    }

    /** The offer of {@code partner} for the order: the PENDING one if any, else the latest. */
    protected UUID offerOf(Order order, UUID partner) {
        List<OfferRow> mine = offerRows(order).stream().filter(row -> row.partnerId().equals(partner)).toList();
        return mine.stream().filter(row -> row.status().equals("PENDING")).findFirst()
                .orElse(mine.getLast()).offerId();
    }

    /** Every delivery event of the order (aggregate Delivery, key orderId), oldest first. */
    protected List<String> eventTypes(Order order) {
        return jdbc().sql("SELECT event_type FROM outbox_event WHERE aggregate_type = ? AND aggregate_id = ? "
                        + "ORDER BY occurred_at, id")
                .param(AGGREGATE).param(order.orderId().toString()).query(String.class).list();
    }

    /** The payloads of the order's events of one type, oldest first. */
    protected List<JsonNode> events(Order order, String eventType) {
        return jdbc().sql("SELECT payload FROM outbox_event WHERE aggregate_type = ? AND aggregate_id = ? "
                        + "AND event_type = ? ORDER BY occurred_at, id")
                .param(AGGREGATE).param(order.orderId().toString()).param(eventType).query(String.class).list()
                .stream().map(json::readTree).toList();
    }

    protected JsonNode event(Order order, String eventType) {
        List<JsonNode> all = events(order, eventType);
        if (all.size() != 1) {
            throw new AssertionError("Expected one " + eventType + " of order " + order.orderId() + ", found "
                    + all.size());
        }
        return all.getFirst();
    }

    /** The routing columns of the order's events. */
    protected List<Map<String, Object>> outboxRows(Order order) {
        return jdbc().sql("SELECT topic, aggregate_type, aggregate_id, event_type FROM outbox_event "
                        + "WHERE aggregate_id = ? ORDER BY occurred_at, id")
                .param(order.orderId().toString()).query().listOfRows();
    }

    protected Map<String, Object> partnerRow(UUID partner) {
        return jdbc().sql("""
                        SELECT online, BIN_TO_UUID(active_delivery_id) AS active_delivery_id, last_latitude,
                               last_longitude, last_location_at
                        FROM delivery_partner WHERE id = UUID_TO_BIN(?)""")
                .param(partner.toString()).query().singleRow();
    }

    protected String activeDelivery(UUID partner) {
        return (String) partnerRow(partner).get("active_delivery_id");
    }

    // ---- API ----------------------------------------------------------------------------------------------------

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
