package com.grocery.order.service;

import com.grocery.common.id.Ids;
import com.grocery.order.config.OrderProperties;
import com.grocery.order.dto.ComplaintDtos.AdminComplaintView;
import com.grocery.order.dto.ComplaintDtos.ComplaintView;
import com.grocery.order.dto.ComplaintDtos.NewComplaintRequest;
import com.grocery.order.dto.ComplaintMapper;
import com.grocery.order.entity.Complaint;
import com.grocery.order.entity.ComplaintStatus;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.repository.ComplaintRepository;
import com.grocery.order.repository.CustomerOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Raising complaints and handling them (docs/api/payment-and-support-agents.md §2).
 * <ul>
 *   <li><b>Raise</b> (the customer): the description is trimmed and checked again (10-1000 characters, else 400
 *       VALIDATION_FAILED); an {@code orderId} must be one of the caller's own orders (else 404 ORDER_NOT_FOUND, as
 *       {@code GET /api/orders/{id}} answers); that order must not have an open complaint (409
 *       COMPLAINT_ALREADY_OPEN, naming it), and the customer must have fewer than {@code max-open} open complaints
 *       (409 TOO_MANY_OPEN_COMPLAINTS). The new complaint is OPEN.</li>
 *   <li><b>Start</b> (an admin): OPEN → IN_PROGRESS, anything else 409 INVALID_COMPLAINT_STATUS.</li>
 *   <li><b>Resolve</b> (an admin): OPEN or IN_PROGRESS → RESOLVED with the answer the customer sees (5-1000
 *       characters, trimmed); a RESOLVED complaint → 409 INVALID_COMPLAINT_STATUS.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * No row locks. Two admins changing the same complaint at once: {@code @Version} fails the second one's update, which
 * grocery-common answers with 409 CONCURRENT_MODIFICATION. Two complaints about the same order at once: the unique
 * key {@code uk_complaint_open_order} stops the second one, answered like the rule (409 COMPLAINT_ALREADY_OPEN). The
 * per-customer limit is only checked before the insert, so two complaints raised at the very same moment may both
 * pass it; it keeps the support queue tidy, nothing depends on it being exact. No events are published.
 */
@Service
public class ComplaintService {

    static final int NUMBER_ATTEMPTS = 5;
    static final String COMPLAINT_NUMBER_KEY = "uk_complaint_number";
    static final String OPEN_COMPLAINT_KEY = "uk_complaint_open_order";
    private static final Logger log = LoggerFactory.getLogger(ComplaintService.class);

    private final ComplaintRepository complaints;
    private final CustomerOrderRepository orders;
    private final OrderNumbers numbers;
    private final OrderProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ComplaintService(ComplaintRepository complaints, CustomerOrderRepository orders, OrderNumbers numbers,
                            OrderProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.complaints = complaints;
        this.orders = orders;
        this.numbers = numbers;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---- the customer -------------------------------------------------------------------------------------------

    /** {@code POST /api/complaints}: a new OPEN complaint of {@code customerId}. */
    public ComplaintView raise(UUID customerId, NewComplaintRequest request) {
        String description = trimmed("description", request.description(),
                Complaint.DESCRIPTION_MIN, Complaint.DESCRIPTION_MAX);
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> {
                    CustomerOrder order = request.orderId() == null ? null : ownOrder(customerId, request.orderId());
                    if (order != null) {
                        complaints.findFirstByOrderIdAndStatusIn(order.getId(), ComplaintStatus.OPEN_STATUSES)
                                .ifPresent(open -> {
                                    throw OrderException.complaintAlreadyOpen(open.getId(), open.getComplaintNumber());
                                });
                    }
                    int maxOpen = properties.getComplaints().getMaxOpen();
                    if (complaints.countByCustomerIdAndStatusIn(customerId, ComplaintStatus.OPEN_STATUSES) >= maxOpen) {
                        throw OrderException.tooManyOpenComplaints(maxOpen);
                    }
                    Complaint complaint = Complaint.raise(Ids.newId(), numbers.nextComplaint(), customerId, order,
                            request.category(), description, clock.instant());
                    complaints.saveAndFlush(complaint);         // a taken number or a second open one fails here
                    log.info("Complaint {} ({}) raised: category {}, order {}", complaint.getComplaintNumber(),
                            complaint.getId(), complaint.getCategory(),
                            order == null ? "none" : order.getOrderNumber());
                    return ComplaintMapper.view(complaint);
                });
            } catch (DataIntegrityViolationException e) {
                if (violates(e, OPEN_COMPLAINT_KEY)) {
                    // another complaint about this order was raised at the same moment: answered like the rule
                    throw openComplaintOf(request.orderId(), e);
                }
                if (!violates(e, COMPLAINT_NUMBER_KEY) || attempt >= NUMBER_ATTEMPTS) {
                    throw e;
                }
                log.warn("Complaint number taken; retrying with a new one (attempt {})", attempt);
            }
        }
    }

    // ---- admins -------------------------------------------------------------------------------------------------

    /** {@code POST /api/admin/complaints/{id}/start}: OPEN → IN_PROGRESS, handled by {@code adminId}. */
    public AdminComplaintView start(UUID adminId, UUID complaintId) {
        return change(complaintId, complaint -> {
            if (!complaint.getStatus().canStart()) {
                throw OrderException.invalidComplaintStatus(complaint.getStatus(), "started");
            }
            complaint.start(adminId, clock.instant());
        });
    }

    /** {@code POST /api/admin/complaints/{id}/resolve}: OPEN or IN_PROGRESS → RESOLVED with {@code resolution}. */
    public AdminComplaintView resolve(UUID adminId, UUID complaintId, String resolution) {
        String answer = trimmed("resolution", resolution, Complaint.RESOLUTION_MIN, Complaint.RESOLUTION_MAX);
        return change(complaintId, complaint -> {
            if (!complaint.getStatus().canResolve()) {
                throw OrderException.invalidComplaintStatus(complaint.getStatus(), "resolved");
            }
            complaint.resolve(answer, adminId, clock.instant());
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * Loads the complaint (404 COMPLAINT_NOT_FOUND), applies {@code change} (status check + transition) and writes it
     * at once: if another admin changed it since it was read, the write fails ({@code @Version} → 409
     * CONCURRENT_MODIFICATION) and nothing is changed.
     */
    private AdminComplaintView change(UUID complaintId, Consumer<Complaint> change) {
        return transaction.execute(status -> {
            Complaint complaint = complaints.findById(complaintId).orElseThrow(OrderException::complaintNotFound);
            ComplaintStatus before = complaint.getStatus();
            change.accept(complaint);
            complaints.saveAndFlush(complaint);
            log.info("Complaint {}: {} → {} by admin {}", complaint.getComplaintNumber(), before,
                    complaint.getStatus(), complaint.getHandledBy());
            return ComplaintMapper.adminView(complaint);
        });
    }

    /** The customer's own order; unknown or someone else's → 404 ORDER_NOT_FOUND (nobody learns that it exists). */
    private CustomerOrder ownOrder(UUID customerId, UUID orderId) {
        return orders.findById(orderId)
                .filter(order -> order.getCustomerId().equals(customerId))
                .orElseThrow(OrderException::orderNotFound);
    }

    /** 409 COMPLAINT_ALREADY_OPEN naming the order's open complaint (or the original failure if it is gone again). */
    private RuntimeException openComplaintOf(UUID orderId, DataIntegrityViolationException failure) {
        return complaints.findFirstByOrderIdAndStatusIn(orderId, ComplaintStatus.OPEN_STATUSES)
                .<RuntimeException>map(open -> OrderException.complaintAlreadyOpen(open.getId(),
                        open.getComplaintNumber()))
                .orElse(failure);
    }

    /**
     * {@code text} without surrounding white space; 400 VALIDATION_FAILED on {@code field} (shaped like a bean
     * validation error) when that is shorter than {@code min} or longer than {@code max} characters.
     */
    static String trimmed(String field, String text, int min, int max) {
        String value = text == null ? "" : text.strip();
        if (value.length() < min || value.length() > max) {
            throw OrderException.validation(List.of(Map.of("field", field,
                    "message", "size must be between " + min + " and " + max)));
        }
        return value;
    }

    private static boolean violates(Throwable failure, String key) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(key)) {
                return true;
            }
        }
        return false;
    }
}
