package com.grocery.order;

import com.grocery.order.repository.ComplaintRepository;
import com.grocery.order.service.ComplaintService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Complaints (docs/api/payment-and-support-agents.md §2): raising one about an own order or about the app, the
 * validation, one open complaint per order, the per-customer limit (default 5), the customer's own list and view, the
 * admin queue with its filters, start and resolve with their roles and status rules, and two admins at once.
 *
 * <p>The database is shared with the other test classes, so every test uses new customers, and admin list checks
 * either search by number ({@code q}) or only look for their own complaints in a filtered page.
 */
class ComplaintsIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> COMPLAINT_VIEW_FIELDS = List.of("id", "complaintNumber", "category", "status",
            "orderId", "orderNumber", "storeName", "description", "resolution", "createdAt", "updatedAt",
            "resolvedAt");
    private static final List<String> ADMIN_VIEW_FIELDS = concat(COMPLAINT_VIEW_FIELDS, "customerId", "handledBy");
    private static final String NUMBER_PATTERN = "CMP-[0-9A-HJKMNP-TV-Z]{8}";

    private static final String LATE = "The order came 2 hours late and the milk was warm.";
    private static final String APP_BROKEN = "The app crashes when I open my cart.";
    private static final String ANSWER = "We are sorry. The delivery fee was refunded to your account.";

    @Autowired
    private ComplaintService complaintService;
    @Autowired
    private ComplaintRepository complaintRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ---------------------------------------------------------------- raising

    @Test
    void aCustomerRaisesAComplaintAboutAnOrderOrAboutTheApp() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        String orderNumber = order(customerId, orderId).get("orderNumber").asString();

        ResultActions created = raise(customerId, "DELIVERY", orderId, "  " + LATE + "  ")
                .andExpect(status().isCreated());
        JsonNode view = body(created);
        created.andExpect(header().string("Location", "/api/complaints/" + view.get("id").asString()));

        assertThat(fields(view)).containsExactlyElementsOf(COMPLAINT_VIEW_FIELDS);
        assertThat(view.get("complaintNumber").asString()).matches(NUMBER_PATTERN);
        assertThat(view.get("category").asString()).isEqualTo("DELIVERY");
        assertThat(view.get("status").asString()).isEqualTo("OPEN");
        assertThat(view.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(view.get("orderNumber").asString()).isEqualTo(orderNumber);           // copied from the order
        assertThat(view.get("storeName").asString()).isEqualTo("Green Basket");
        assertThat(view.get("description").asString()).isEqualTo(LATE);                  // trimmed
        assertThat(view.get("resolution").isNull()).isTrue();
        assertThat(view.get("resolvedAt").isNull()).isTrue();
        assertThat(view.get("updatedAt").asString()).isEqualTo(view.get("createdAt").asString());

        // about the app: no order, no store
        JsonNode general = raised(customerId, "APP", null, APP_BROKEN);
        assertThat(fields(general)).containsExactlyElementsOf(COMPLAINT_VIEW_FIELDS);
        assertThat(general.get("category").asString()).isEqualTo("APP");
        assertThat(general.get("orderId").isNull()).isTrue();
        assertThat(general.get("orderNumber").isNull()).isTrue();
        assertThat(general.get("storeName").isNull()).isTrue();
        assertThat(general.get("complaintNumber").asString()).isNotEqualTo(view.get("complaintNumber").asString());

        // the customer reads it back, the customer id is stored from the token
        assertThat(myComplaint(customerId, view.get("id").asString())).isEqualTo(view);
        assertThat(jdbc().sql("SELECT BIN_TO_UUID(customer_id) FROM complaint WHERE id = UUID_TO_BIN(?)")
                .param(view.get("id").asString()).query(String.class).single()).isEqualTo(customerId.toString());

        // a restaurant order's store name is copied too
        UUID restaurantCustomer = UUID.randomUUID();
        UUID restaurantOrder = newRestaurantOrder(restaurantCustomer);
        assertThat(raised(restaurantCustomer, "STORE", restaurantOrder, "The biryani was cold and very salty.")
                .get("storeName").asString()).isEqualTo("Spice Route");
    }

    @Test
    void theOrderMustBeTheCustomersOwn() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID orderId = newGroceryOrder(owner);
        UUID someoneElse = UUID.randomUUID();

        raise(someoneElse, "DELIVERY", orderId, LATE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
        raise(someoneElse, "DELIVERY", UUID.randomUUID(), LATE)                            // no such order
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));

        assertThat(myComplaints(someoneElse).get("totalElements").asLong()).isZero();
        raise(owner, "DELIVERY", orderId, LATE).andExpect(status().isCreated());
    }

    @Test
    void invalidComplaintsAreRejectedWithFieldErrors() throws Exception {
        UUID customerId = UUID.randomUUID();

        JsonNode tooShort = body(raise(customerId, "DELIVERY", null, "Too late")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED")));
        assertThat(tooShort.get("errors").get(0).get("field").asString()).isEqualTo("description");
        // 14 characters with the spaces, 5 without: checked after trimming
        JsonNode shortWhenTrimmed = body(raise(customerId, "DELIVERY", null, "     Late     ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED")));
        assertThat(shortWhenTrimmed.get("errors").get(0).get("field").asString()).isEqualTo("description");
        raise(customerId, "DELIVERY", null, "x".repeat(1001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        raise(customerId, "DELIVERY", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        JsonNode noCategory = body(raise(customerId, null, null, LATE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED")));
        assertThat(noCategory.get("errors").get(0).get("field").asString()).isEqualTo("category");
        raise(customerId, "FOOD", null, LATE)                                              // unknown category
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_ERROR"));                 // an unreadable body

        // exactly 10 and 1000 characters are fine
        raise(customerId, "OTHER", null, "x".repeat(10)).andExpect(status().isCreated());
        raise(customerId, "OTHER", null, "x".repeat(1000)).andExpect(status().isCreated());
        assertThat(myComplaints(customerId).get("totalElements").asLong()).isEqualTo(2);

        // customers only
        mvc.perform(post("/api/complaints").with(as(UUID.randomUUID(), "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("category", "APP", "description", APP_BROKEN))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOrderHasOneOpenComplaintAtATime() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        JsonNode first = raised(customerId, "DELIVERY", orderId, LATE);
        String number = first.get("complaintNumber").asString();

        JsonNode refused = body(raise(customerId, "ITEMS", orderId, "One packet of dal was torn open.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COMPLAINT_ALREADY_OPEN"))
                .andExpect(jsonPath("$.complaintNumber").value(number))
                .andExpect(jsonPath("$.complaintId").value(first.get("id").asString())));
        assertThat(refused.get("detail").asString()).contains(number);

        // IN_PROGRESS is still open
        start(as(UUID.randomUUID(), "SUPPORT"), first.get("id").asString()).andExpect(status().isOk());
        raise(customerId, "ITEMS", orderId, "One packet of dal was torn open.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COMPLAINT_ALREADY_OPEN"));

        // other orders and complaints without an order are not affected
        UUID otherOrder = newGroceryOrder(customerId);
        raise(customerId, "PAYMENT", otherOrder, "I was charged twice for this order.").andExpect(status().isCreated());
        raise(customerId, "APP", null, APP_BROKEN).andExpect(status().isCreated());

        // once it is resolved, the order can get a new complaint
        resolve(as(UUID.randomUUID(), "SUPPORT"), first.get("id").asString(), ANSWER).andExpect(status().isOk());
        JsonNode second = raised(customerId, "ITEMS", orderId, "One packet of dal was torn open.");
        assertThat(second.get("status").asString()).isEqualTo("OPEN");
        assertThat(second.get("complaintNumber").asString()).isNotEqualTo(number);
    }

    @Test
    void aCustomerHasAtMostFiveOpenComplaints() throws Exception {
        UUID customerId = UUID.randomUUID();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(raised(customerId, "OTHER", null, "Complaint number " + i + " about the app.").get("id")
                    .asString());
        }

        raise(customerId, "OTHER", null, "Complaint number 6 about the app.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_OPEN_COMPLAINTS"))
                .andExpect(jsonPath("$.maxOpen").value(5));
        UUID orderId = newGroceryOrder(customerId);                    // with an order (none open for it) as well
        raise(customerId, "DELIVERY", orderId, LATE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_OPEN_COMPLAINTS"));
        // IN_PROGRESS ones count too
        start(as(UUID.randomUUID(), "OPS_ADMIN"), ids.getFirst()).andExpect(status().isOk());
        raise(customerId, "APP", null, APP_BROKEN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_OPEN_COMPLAINTS"));

        // another customer has their own limit
        raise(UUID.randomUUID(), "APP", null, APP_BROKEN).andExpect(status().isCreated());

        // a resolved complaint no longer counts
        resolve(as(UUID.randomUUID(), "SUPPORT"), ids.getFirst(), ANSWER).andExpect(status().isOk());
        raise(customerId, "DELIVERY", orderId, LATE).andExpect(status().isCreated());
        assertThat(myComplaints(customerId).get("totalElements").asLong()).isEqualTo(6);
    }

    // ---------------------------------------------------------------- the customer's list and view

    @Test
    void theCustomerSeesOnlyTheirOwnComplaintsNewestFirst() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        String oldest = raised(customerId, "APP", null, APP_BROKEN).get("id").asString();
        String middle = raised(customerId, "OTHER", null, "Nobody answers the support phone line.").get("id")
                .asString();
        String newest = raised(customerId, "DELIVERY", orderId, LATE).get("id").asString();
        UUID someoneElse = UUID.randomUUID();
        String theirs = raised(someoneElse, "APP", null, APP_BROKEN).get("id").asString();

        JsonNode mine = myComplaints(customerId);
        assertThat(fields(mine)).containsExactly("content", "page", "size", "totalElements", "totalPages");
        assertThat(ids(mine)).containsExactly(newest, middle, oldest);
        assertThat(mine.get("totalElements").asLong()).isEqualTo(3);
        assertThat(fields(mine.get("content").get(0))).containsExactlyElementsOf(COMPLAINT_VIEW_FIELDS);
        assertThat(ids(myComplaints(someoneElse))).containsExactly(theirs);

        // paging
        JsonNode firstPage = myComplaints(customerId, "size", "2");
        assertThat(ids(firstPage)).containsExactly(newest, middle);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(myComplaints(customerId, "size", "2", "page", "1"))).containsExactly(oldest);

        // the status filter
        resolve(as(UUID.randomUUID(), "SUPPORT"), middle, ANSWER).andExpect(status().isOk());
        assertThat(ids(myComplaints(customerId, "status", "RESOLVED"))).containsExactly(middle);
        assertThat(ids(myComplaints(customerId, "status", "OPEN"))).containsExactly(newest, oldest);
        assertThat(ids(myComplaints(customerId, "status", "IN_PROGRESS"))).isEmpty();
        mvc.perform(get("/api/complaints").param("status", "CLOSED").with(customer(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        // someone else's complaint, or none at all, is not found
        mvc.perform(get("/api/complaints/{id}", oldest).with(customer(someoneElse)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMPLAINT_NOT_FOUND"));
        mvc.perform(get("/api/complaints/{id}", UUID.randomUUID()).with(customer(customerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMPLAINT_NOT_FOUND"));

        // customers only
        mvc.perform(get("/api/complaints").with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- admins

    @Test
    void adminsListAndFilterEveryCustomersComplaints() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        String orderNumber = order(customerId, orderId).get("orderNumber").asString();
        JsonNode payment = raised(customerId, "PAYMENT", orderId, "I was charged twice for this order.");
        JsonNode app = raised(customerId, "APP", null, APP_BROKEN);
        String paymentNumber = payment.get("complaintNumber").asString();
        RequestPostProcessor finance = as(UUID.randomUUID(), "FINANCE_ADMIN");

        // by complaint number (any case) and by order number
        JsonNode byNumber = adminList(finance, "q", " " + paymentNumber.toLowerCase(Locale.ROOT) + " ");
        assertThat(ids(byNumber)).containsExactly(payment.get("id").asString());
        JsonNode adminView = byNumber.get("content").get(0);
        assertThat(fields(adminView)).containsExactlyElementsOf(ADMIN_VIEW_FIELDS);
        assertThat(adminView.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(adminView.get("handledBy").isNull()).isTrue();
        assertThat(adminView.get("orderNumber").asString()).isEqualTo(orderNumber);
        assertThat(ids(adminList(finance, "q", orderNumber))).containsExactly(payment.get("id").asString());
        assertThat(ids(adminList(finance, "q", app.get("complaintNumber").asString())))
                .containsExactly(app.get("id").asString());
        assertThat(ids(adminList(finance, "q", "CMP-00000000"))).isEmpty();

        // category and status, with and without a search
        assertThat(ids(adminList(finance, "q", orderNumber, "category", "PAYMENT"))).hasSize(1);
        assertThat(ids(adminList(finance, "q", orderNumber, "category", "APP"))).isEmpty();
        assertThat(ids(adminList(finance, "q", paymentNumber, "status", "OPEN"))).hasSize(1);
        assertThat(ids(adminList(finance, "q", paymentNumber, "status", "RESOLVED"))).isEmpty();

        UUID support = UUID.randomUUID();
        start(as(support, "SUPPORT"), app.get("id").asString()).andExpect(status().isOk());
        JsonNode inProgress = adminList(finance, "status", "IN_PROGRESS", "size", "100");
        assertThat(inProgress.get("content").valueStream().map(c -> c.get("status").asString()))
                .containsOnly("IN_PROGRESS");
        assertThat(ids(inProgress)).contains(app.get("id").asString())
                .doesNotContain(payment.get("id").asString());
        JsonNode payments = adminList(finance, "category", "PAYMENT", "size", "100");
        assertThat(payments.get("content").valueStream().map(c -> c.get("category").asString()))
                .containsOnly("PAYMENT");
        assertThat(ids(payments)).contains(payment.get("id").asString())
                .doesNotContain(app.get("id").asString());

        // no filter: every status, newest first (the app complaint was raised last)
        JsonNode everything = adminList(as(UUID.randomUUID(), "DELIVERY_MANAGER"), "size", "100");
        List<String> all = ids(everything);
        assertThat(all).contains(app.get("id").asString(), payment.get("id").asString());
        assertThat(all.indexOf(app.get("id").asString())).isLessThan(all.indexOf(payment.get("id").asString()));
        List<Instant> created = everything.get("content").valueStream()
                .map(c -> Instant.parse(c.get("createdAt").asString())).toList();
        assertThat(created).isSortedAccordingTo((a, b) -> b.compareTo(a));

        // bad filters, and customers may not see the queue
        mvc.perform(get("/api/admin/complaints").param("category", "FOOD").with(finance))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/complaints").param("status", "CLOSED").with(finance))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/complaints").with(customer(customerId))).andExpect(status().isForbidden());
    }

    @Test
    void supportStartsAndResolvesAComplaintAndTheCustomerSeesTheAnswer() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        JsonNode raised = raised(customerId, "DELIVERY", orderId, LATE);
        String id = raised.get("id").asString();

        // who may handle it
        start(as(UUID.randomUUID(), "FINANCE_ADMIN"), id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        resolve(as(UUID.randomUUID(), "FINANCE_ADMIN"), id, ANSWER).andExpect(status().isForbidden());
        resolve(as(UUID.randomUUID(), "DELIVERY_MANAGER"), id, ANSWER).andExpect(status().isForbidden());
        start(customer(customerId), id).andExpect(status().isForbidden());
        resolve(customer(customerId), id, ANSWER).andExpect(status().isForbidden());
        assertThat(myComplaint(customerId, id).get("status").asString()).isEqualTo("OPEN");

        // OPEN → IN_PROGRESS
        UUID support = UUID.randomUUID();
        JsonNode started = body(start(as(support, "SUPPORT"), id).andExpect(status().isOk()));
        assertThat(fields(started)).containsExactlyElementsOf(ADMIN_VIEW_FIELDS);
        assertThat(started.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(started.get("handledBy").asString()).isEqualTo(support.toString());
        assertThat(started.get("resolution").isNull()).isTrue();
        assertThat(started.get("resolvedAt").isNull()).isTrue();
        assertThat(Instant.parse(started.get("updatedAt").asString()))
                .isAfterOrEqualTo(Instant.parse(raised.get("createdAt").asString()));
        start(as(support, "SUPPORT"), id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_COMPLAINT_STATUS"))
                .andExpect(jsonPath("$.currentStatus").value("IN_PROGRESS"));

        // the resolution is validated (after trimming)
        JsonNode tooShort = body(resolve(as(support, "SUPPORT"), id, "Ok")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED")));
        assertThat(tooShort.get("errors").get(0).get("field").asString()).isEqualTo("resolution");
        resolve(as(support, "SUPPORT"), id, "     Ok     ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        resolve(as(support, "SUPPORT"), id, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // IN_PROGRESS → RESOLVED by another admin
        UUID ops = UUID.randomUUID();
        JsonNode resolved = body(resolve(as(ops, "OPS_ADMIN"), id, "  " + ANSWER + "  ").andExpect(status().isOk()));
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolution").asString()).isEqualTo(ANSWER);
        assertThat(resolved.get("handledBy").asString()).isEqualTo(ops.toString());
        assertThat(resolved.get("resolvedAt").asString()).isEqualTo(resolved.get("updatedAt").asString());

        // RESOLVED is final
        resolve(as(ops, "OPS_ADMIN"), id, "Another answer.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_COMPLAINT_STATUS"))
                .andExpect(jsonPath("$.currentStatus").value("RESOLVED"));
        start(as(ops, "SUPER_ADMIN"), id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_COMPLAINT_STATUS"));

        // the customer sees the answer (but not who wrote it)
        JsonNode seen = myComplaint(customerId, id);
        assertThat(fields(seen)).containsExactlyElementsOf(COMPLAINT_VIEW_FIELDS);
        assertThat(seen.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(seen.get("resolution").asString()).isEqualTo(ANSWER);
        assertThat(seen.get("resolvedAt").asString()).isEqualTo(resolved.get("resolvedAt").asString());
        assertThat(ids(myComplaints(customerId, "status", "RESOLVED"))).containsExactly(id);

        // OPEN → RESOLVED directly
        JsonNode direct = body(resolve(as(UUID.randomUUID(), "SUPER_ADMIN"),
                raised(customerId, "APP", null, APP_BROKEN).get("id").asString(), "Fixed in the latest version.")
                .andExpect(status().isOk()));
        assertThat(direct.get("status").asString()).isEqualTo("RESOLVED");

        // unknown complaint
        start(as(support, "SUPPORT"), UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMPLAINT_NOT_FOUND"));
        resolve(as(support, "SUPPORT"), UUID.randomUUID().toString(), ANSWER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMPLAINT_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- two admins at once

    /**
     * Admin 2 read the complaint while it was OPEN; admin 1 started it in the meantime. Admin 2's write is on a stale
     * version and fails ({@code @Version}), which grocery-common answers with 409 CONCURRENT_MODIFICATION; admin 1's
     * change stays.
     */
    @Test
    void aChangeOnAStaleComplaintFailsAndKeepsTheOtherAdminsChange() throws Exception {
        UUID customerId = UUID.randomUUID();
        String id = raised(customerId, "OTHER", null, "Nobody answers the support phone line.").get("id").asString();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        TransactionTemplate secondAdmin = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> secondAdmin.executeWithoutResult(status -> {
            assertThat(complaintRepository.findById(UUID.fromString(id)).orElseThrow().getStatus().name())
                    .isEqualTo("OPEN");                                                // read: OPEN, version 0
            assertThat(startedElsewhere(first, id)).isEqualTo(200);                    // admin 1 starts it meanwhile
            complaintService.resolve(second, UUID.fromString(id), ANSWER);              // writes on version 0
        })).isInstanceOf(OptimisticLockingFailureException.class);

        JsonNode now = adminList(as(UUID.randomUUID(), "SUPPORT"), "q",
                myComplaint(customerId, id).get("complaintNumber").asString()).get("content").get(0);
        assertThat(now.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(now.get("handledBy").asString()).isEqualTo(first.toString());
        assertThat(now.get("resolution").isNull()).isTrue();
    }

    /** Two admins resolving the same complaint at the same moment: one wins, the other gets a 409. */
    @Test
    void twoAdminsResolvingAtOnceOnlyOneWins() throws Exception {
        for (int round = 0; round < 3; round++) {
            String id = raised(UUID.randomUUID(), "OTHER", null, "Nobody answers the support phone line.")
                    .get("id").asString();
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> answers = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (String role : List.of("SUPPORT", "OPS_ADMIN")) {
                    answers.add(pool.submit(() -> {
                        go.await();
                        return resolve(as(UUID.randomUUID(), role), id, "Answered by " + role + ".")
                                .andReturn().getResponse();
                    }));
                }
                go.countDown();
            }
            List<Integer> statuses = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            for (Future<MockHttpServletResponse> answer : answers) {
                MockHttpServletResponse response = answer.get(30, TimeUnit.SECONDS);
                statuses.add(response.getStatus());
                if (response.getStatus() == 409) {
                    conflicts.add(json.readTree(response.getContentAsString()).get("errorCode").asString());
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            // both read OPEN → the optimistic lock; the second read RESOLVED → the status rule
            assertThat(conflicts).hasSize(1);
            assertThat(conflicts.getFirst()).isIn("CONCURRENT_MODIFICATION", "INVALID_COMPLAINT_STATUS");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** {@code POST /api/complaints} as the customer; null fields are left out of the body. */
    private ResultActions raise(UUID customerId, String category, UUID orderId, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (category != null) {
            body.put("category", category);
        }
        if (orderId != null) {
            body.put("orderId", orderId.toString());
        }
        if (description != null) {
            body.put("description", description);
        }
        return mvc.perform(post("/api/complaints").with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode raised(UUID customerId, String category, UUID orderId, String description) throws Exception {
        return body(raise(customerId, category, orderId, description).andExpect(status().isCreated()));
    }

    private ResultActions start(RequestPostProcessor caller, String complaintId) throws Exception {
        return mvc.perform(post("/api/admin/complaints/{id}/start", complaintId).with(caller));
    }

    /** {@code POST …/resolve}; a null resolution sends an empty body object. */
    private ResultActions resolve(RequestPostProcessor caller, String complaintId, String resolution)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (resolution != null) {
            body.put("resolution", resolution);
        }
        return mvc.perform(post("/api/admin/complaints/{id}/resolve", complaintId).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** {@code GET /api/complaints} with query parameters as name/value pairs. */
    private JsonNode myComplaints(UUID customerId, String... parameters) throws Exception {
        return body(mvc.perform(withParameters(get("/api/complaints"), parameters).with(customer(customerId)))
                .andExpect(status().isOk()));
    }

    private JsonNode myComplaint(UUID customerId, String complaintId) throws Exception {
        return body(mvc.perform(get("/api/complaints/{id}", complaintId).with(customer(customerId)))
                .andExpect(status().isOk()));
    }

    /** {@code GET /api/admin/complaints} with query parameters as name/value pairs. */
    private JsonNode adminList(RequestPostProcessor caller, String... parameters) throws Exception {
        return body(mvc.perform(withParameters(get("/api/admin/complaints"), parameters).with(caller))
                .andExpect(status().isOk()));
    }

    private static MockHttpServletRequestBuilder withParameters(MockHttpServletRequestBuilder request,
                                                                String... parameters) {
        for (int i = 0; i < parameters.length; i += 2) {
            request = request.param(parameters[i], parameters[i + 1]);
        }
        return request;
    }

    /** The ids of a page's complaints, in order. */
    private static List<String> ids(JsonNode page) {
        return page.get("content").valueStream().map(complaint -> complaint.get("id").asString()).toList();
    }

    /**
     * {@code adminId} (SUPPORT) starts the complaint in a request of its own: on another thread, so in its own
     * transaction, committed when this returns. Returns the HTTP status.
     */
    private int startedElsewhere(UUID adminId, String complaintId) {
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            return other.submit(() -> start(as(adminId, "SUPPORT"), complaintId).andReturn().getResponse().getStatus())
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> concat(List<String> first, String... more) {
        List<String> all = new ArrayList<>(first);
        all.addAll(List.of(more));
        return List.copyOf(all);
    }
}
