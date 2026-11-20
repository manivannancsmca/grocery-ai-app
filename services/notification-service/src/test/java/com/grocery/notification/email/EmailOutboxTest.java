package com.grocery.notification.email;

import com.grocery.notification.dispatch.NotificationDraft;
import com.grocery.notification.entity.NotificationType;
import com.grocery.notification.repository.EmailAddressRepository;
import com.grocery.notification.repository.EmailAddressRepository.Recipient;
import com.grocery.notification.repository.EmailOutboxRepository;
import com.grocery.notification.repository.EmailOutboxRepository.NewEmail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Phase 12 §5: which notifications are emailed to whom (without Spring; the repositories are mocks). */
class EmailOutboxTest {

    private final EmailAddressRepository addresses = mock(EmailAddressRepository.class);
    private final EmailOutboxRepository outbox = mock(EmailOutboxRepository.class);

    @Test
    void theDefaultTypesAreTheContractsAndTheyMapToThePreferenceCategories() {
        assertThat(EmailProperties.DEFAULT_TYPES).containsExactlyInAnyOrder(
                NotificationType.ORDER_CONFIRMED, NotificationType.ORDER_CANCELLED, NotificationType.ORDER_DELIVERED,
                NotificationType.REFUND_COMPLETED, NotificationType.REFUND_FAILED, NotificationType.RETURN_APPROVED,
                NotificationType.RETURN_REJECTED, NotificationType.ACCOUNT_APPROVED, NotificationType.ACCOUNT_REJECTED,
                NotificationType.ACCOUNT_SUSPENDED, NotificationType.AI_APPROVAL_REQUESTED);
        assertThat(new EmailProperties().getTypes()).isEqualTo(EmailProperties.DEFAULT_TYPES);
        assertThat(new EmailProperties().isEnabled()).as("off unless configured").isFalse();
        assertThat(new EmailProperties().getRetryDelays()).extracting(java.time.Duration::toMinutes)
                .containsExactly(1L, 5L, 30L);
        for (NotificationType type : List.of(NotificationType.ORDER_CONFIRMED, NotificationType.ORDER_CANCELLED,
                NotificationType.ORDER_DELIVERED, NotificationType.REFUND_COMPLETED, NotificationType.REFUND_FAILED,
                NotificationType.RETURN_APPROVED, NotificationType.RETURN_REJECTED)) {
            assertThat(EmailCategory.of(type)).as(type.name()).isEqualTo(EmailCategory.ORDERS);
        }
        for (NotificationType type : List.of(NotificationType.ACCOUNT_APPROVED, NotificationType.ACCOUNT_REJECTED,
                NotificationType.ACCOUNT_SUSPENDED)) {
            assertThat(EmailCategory.of(type)).as(type.name()).isEqualTo(EmailCategory.ACCOUNT);
        }
        assertThat(EmailCategory.of(NotificationType.AI_APPROVAL_REQUESTED)).isEqualTo(EmailCategory.APPROVALS);
        assertThat(EmailCategory.APPROVALS.column()).isEqualTo("approvals");
    }

    @Test
    void usersAndRolesOfTheAudiencesAreResolvedWithTheTypesCategory() {
        UUID notificationId = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(addresses.recipients(anyCollection(), anyCollection(), eq(EmailCategory.ORDERS))).thenReturn(List.of(
                new Recipient(customer, "asha@example.com", "Asha"), new Recipient(admin, "ops@example.com", null)));
        when(outbox.insert(any())).thenReturn(true);
        Instant now = Instant.parse("2026-11-13T09:00:00Z");

        outbox(true, EmailProperties.DEFAULT_TYPES).enqueue(notificationId, draft(NotificationType.REFUND_FAILED),
                new LinkedHashSet<>(List.of("user:" + customer, "role:FINANCE_ADMIN", "role:SUPER_ADMIN",
                        "user:not-a-uuid", "public")), now);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> users = ArgumentCaptor.forClass(java.util.Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> roles = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(addresses).recipients(users.capture(), roles.capture(), eq(EmailCategory.ORDERS));
        assertThat(users.getValue()).containsExactly(customer);
        assertThat(roles.getValue()).containsExactly("FINANCE_ADMIN", "SUPER_ADMIN");
        ArgumentCaptor<NewEmail> emails = ArgumentCaptor.forClass(NewEmail.class);
        verify(outbox, times(2)).insert(emails.capture());
        assertThat(emails.getAllValues()).extracting(NewEmail::userId).containsExactly(customer, admin);
        assertThat(emails.getAllValues().getFirst()).satisfies(email -> {
            assertThat(email.notificationId()).isEqualTo(notificationId);
            assertThat(email.toAddress()).isEqualTo("asha@example.com");
            assertThat(email.toName()).isEqualTo("Asha");
            assertThat(email.type()).isEqualTo("REFUND_FAILED");
            assertThat(email.subject()).isEqualTo("Title");
            assertThat(email.body()).isEqualTo("Body");
            assertThat(email.link()).isEqualTo("/admin/payments");
            assertThat(email.createdAt()).isEqualTo(now);
        });
    }

    @Test
    void nothingIsQueuedWhenTheChannelIsOffOrTheTypeIsInAppOnly() {
        Set<String> audiences = Set.of("user:" + UUID.randomUUID());
        outbox(false, EmailProperties.DEFAULT_TYPES).enqueue(UUID.randomUUID(),
                draft(NotificationType.ORDER_CONFIRMED), audiences, Instant.now());
        outbox(true, EmailProperties.DEFAULT_TYPES).enqueue(UUID.randomUUID(), draft(NotificationType.NEW_REVIEW),
                audiences, Instant.now());
        outbox(true, EnumSet.of(NotificationType.NEW_REVIEW)).enqueue(UUID.randomUUID(),
                draft(NotificationType.ORDER_CONFIRMED), audiences, Instant.now());
        verifyNoInteractions(addresses, outbox);
    }

    @Test
    void aConfiguredExtraTypeIsEmailedUnderItsCategory() {
        when(addresses.recipients(anyCollection(), anyCollection(), any())).thenReturn(List.of());
        outbox(true, EnumSet.of(NotificationType.NEW_REVIEW, NotificationType.APPROVAL_REQUESTED))
                .enqueue(UUID.randomUUID(), draft(NotificationType.APPROVAL_REQUESTED), Set.of("role:OPS_ADMIN"),
                        Instant.now());
        verify(addresses).recipients(argThat(java.util.Collection::isEmpty), eq(Set.of("OPS_ADMIN")),
                eq(EmailCategory.APPROVALS));
    }

    @Test
    void usableAddresses() {
        assertThat(EmailAddressRepository.usable("asha@example.com")).isTrue();
        assertThat(EmailAddressRepository.usable(" asha@example.com ")).isTrue();
        assertThat(EmailAddressRepository.usable(null)).isFalse();
        assertThat(EmailAddressRepository.usable("no-at-sign")).isFalse();
        assertThat(EmailAddressRepository.usable("@example.com")).isFalse();
        assertThat(EmailAddressRepository.usable("a@")).isFalse();
        assertThat(EmailAddressRepository.usable("a@b@c")).isFalse();
        assertThat(EmailAddressRepository.usable("a b@example.com")).isFalse();
        assertThat(EmailAddressRepository.usable("Evil <a@example.com>")).isFalse();
        assertThat(EmailAddressRepository.usable("a@example.com\r\nBcc: b@example.com")).isFalse();
        assertThat(EmailAddressRepository.usable("x".repeat(250) + "@e.co")).isFalse();
    }

    private EmailOutbox outbox(boolean enabled, Set<NotificationType> types) {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(enabled);
        properties.setTypes(types);
        return new EmailOutbox(properties, addresses, outbox);
    }

    private static NotificationDraft draft(NotificationType type) {
        return new NotificationDraft(type, "Title", "Body", "/admin/payments", Map.of());
    }
}
