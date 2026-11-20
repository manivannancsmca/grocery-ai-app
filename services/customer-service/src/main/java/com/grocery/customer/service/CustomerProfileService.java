package com.grocery.customer.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.NotFoundException;
import com.grocery.customer.dto.CustomerDtos.CustomerProfile;
import com.grocery.customer.dto.CustomerDtos.ProfileUpdateRequest;
import com.grocery.customer.entity.Customer;
import com.grocery.customer.event.CustomerEvents;
import com.grocery.customer.mapper.CustomerMapper;
import com.grocery.customer.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** The customer's own profile: creation (registration event or on demand) and editing (CustomerProfileUpdated). */
@Service
public class CustomerProfileService {

    public static final String IDENTITY_CONSUMER = "customer-service.identity-events";
    private static final Logger log = LoggerFactory.getLogger(CustomerProfileService.class);

    private final CustomerRepository customers;
    private final ProcessedEventGuard processedEvents;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public CustomerProfileService(CustomerRepository customers, ProcessedEventGuard processedEvents,
                                  OutboxPublisher outbox, Clock clock) {
        this.customers = customers;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** UserRegistered(role=CUSTOMER) → customer profile (idempotent). */
    @Transactional
    public void onCustomerUserRegistered(String eventId, UUID userId, String fullName, String email, String phone) {
        if (!processedEvents.markProcessed(eventId, IDENTITY_CONSUMER)) {
            return;
        }
        if (customers.existsById(userId)) {
            return; // already created on demand by the customer's first save
        }
        create(userId, email, fullName, phone);
        log.info("Created customer profile for user {}", userId);
    }

    @Transactional(readOnly = true)
    public CustomerProfile myProfile(CurrentUser user) {
        return CustomerMapper.toView(customers.findById(user.userId()).orElseThrow(CustomerProfileService::notFound));
    }

    /**
     * Saves the profile. If the registration event has not arrived yet the profile is created here,
     * so the UI never depends on event timing.
     *
     * <p>When the name or the phone changed, {@code CustomerProfileUpdated} is published in the same transaction, so
     * the account (identity: header, JWT name claim) and the email greetings (notification) follow. Saving the same
     * details again publishes nothing.
     */
    @Transactional
    public CustomerProfile updateMyProfile(CurrentUser user, ProfileUpdateRequest request) {
        Customer customer = customers.findById(user.userId())
                .orElseGet(() -> create(user.userId(), user.email(), user.name(), null));
        String fullName = CustomerMapper.trim(request.fullName());
        String phone = CustomerMapper.trim(request.phone());
        boolean changed = !Objects.equals(fullName, customer.getFullName()) || !Objects.equals(phone, customer.getPhone());
        customer.updateDetails(fullName, phone, clock.instant());
        if (changed) {
            outbox.publish(OutboxEvent.of(CustomerEvents.TOPIC, CustomerEvents.AGGREGATE, customer.getId().toString(),
                    "CustomerProfileUpdated", new CustomerEvents.CustomerProfileUpdated(customer.getId(),
                            customer.getFullName(), customer.getPhone(), customer.getUpdatedAt())));
        }
        return CustomerMapper.toView(customer);
    }

    /**
     * The caller's profile, row-locked for an address-book change; created from the JWT if it does not
     * exist yet. Joins the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Customer lockOrCreate(CurrentUser user) {
        // Existence check first: a locking read of a missing row takes a gap lock, and two concurrent
        // first saves would then deadlock on insert instead of one failing cleanly on the primary key.
        if (!customers.existsById(user.userId())) {
            return create(user.userId(), user.email(), user.name(), null);
        }
        return customers.findByIdForUpdate(user.userId()).orElseThrow(CustomerProfileService::notFound);
    }

    private Customer create(UUID userId, String email, String fullName, String phone) {
        return customers.saveAndFlush(Customer.create(userId, email, fullName, phone, clock.instant()));
    }

    static NotFoundException notFound() {
        return new NotFoundException("CUSTOMER_PROFILE_NOT_FOUND", "Customer profile not found");
    }
}
