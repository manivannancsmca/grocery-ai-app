package com.grocery.notification.service;

import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.notification.dto.EmailDtos.EmailLogEntry;
import com.grocery.notification.dto.EmailDtos.EmailPreferencesRequest;
import com.grocery.notification.dto.EmailDtos.EmailPreferencesView;
import com.grocery.notification.dto.EmailDtos.Preferences;
import com.grocery.notification.dto.EmailDtos.PreferencesRequest;
import com.grocery.notification.dto.EmailExports;
import com.grocery.notification.repository.EmailOutboxRepository;
import com.grocery.notification.repository.EmailOutboxRepository.OutboxEmail;
import com.grocery.notification.repository.EmailPreferenceRepository;
import com.grocery.notification.repository.EmailPreferenceRepository.EmailPreferences;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 12 §5: the caller's email preferences ({@code GET/PUT /api/notifications/preferences}, default all on) and the
 * admin email log ({@code GET /api/admin/notifications/emails}) with its export.
 */
@Service
public class EmailSettingsService {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    private static final Set<String> STATUSES = Set.of(EmailOutboxRepository.PENDING, EmailOutboxRepository.SENT,
            EmailOutboxRepository.FAILED);

    private final EmailPreferenceRepository preferences;
    private final EmailOutboxRepository outbox;
    /** The export's batches: one short read-only transaction each. */
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public EmailSettingsService(EmailPreferenceRepository preferences, EmailOutboxRepository outbox,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.preferences = preferences;
        this.outbox = outbox;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Preferences preferences(CurrentUser user) {
        return view(preferences.find(user.userId()).orElse(EmailPreferences.DEFAULT));
    }

    /** A missing category keeps its value; no {@code email} object → 400 VALIDATION_FAILED. */
    @Transactional
    public Preferences update(CurrentUser user, PreferencesRequest request) {
        if (request == null || request.email() == null) {
            throw new BadRequestException("VALIDATION_FAILED", "The body must contain an 'email' object");
        }
        EmailPreferences current = preferences.find(user.userId()).orElse(EmailPreferences.DEFAULT);
        EmailPreferencesRequest change = request.email();
        EmailPreferences updated = new EmailPreferences(
                change.orders() == null ? current.orders() : change.orders(),
                change.account() == null ? current.account() : change.account(),
                change.approvals() == null ? current.approvals() : change.approvals());
        preferences.save(user.userId(), updated, clock.instant());
        return view(updated);
    }

    /** Newest first; {@code status} PENDING | SENT | FAILED (else 400 INVALID_PARAMETER); 1 ≤ size ≤ 100. */
    @Transactional(readOnly = true)
    public PageResponse<EmailLogEntry> log(String status, String to, Integer page, Integer size) {
        String statusFilter = statusFilter(status);
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        long total = outbox.count(statusFilter, to);
        List<EmailLogEntry> content = total == 0 ? List.of()
                : outbox.page(statusFilter, to, safeSize, (long) safePage * safeSize).stream()
                .map(EmailSettingsService::entry).toList();
        int totalPages = (int) ((total + safeSize - 1) / safeSize);
        return new PageResponse<>(content, safePage, safeSize, total, totalPages);
    }

    /**
     * {@code GET /api/admin/notifications/emails/export}: the log's filters, every page, newest first or by
     * {@code sort}. The log is plain JDBC (no JPA, so not grocery-common's {@code ExportQueries}): the same keyset
     * batches by hand — each batch is the next rows after the last one seen ({@link EmailOutboxRepository#logBatch},
     * never OFFSET), read in its own short read-only transaction, so no connection is held while a batch is written
     * to the client. Not {@code @Transactional}: call it from the controller.
     */
    public RowSource<EmailLogEntry> logExport(String status, String to, String sort) {
        String statusFilter = statusFilter(status);
        Order<EmailLogEntry> order = EmailExports.LOG_SORT.resolve(sort);
        return new RowSource<>() {
            @Override
            public long count() {
                Long count = readOnly.execute(transaction -> outbox.count(statusFilter, to));
                return count == null ? 0 : count;
            }

            @Override
            public void forEachBatch(int batchSize, BatchHandler<? super EmailLogEntry> handler) throws IOException {
                Object[] after = null;
                while (true) {
                    Object[] boundary = after;
                    List<EmailLogEntry> batch = readOnly.execute(transaction -> outbox.logBatch(statusFilter, to,
                            order.keys(), boundary, batchSize).stream().map(EmailSettingsService::entry).toList());
                    if (batch == null || batch.isEmpty() || !handler.handle(batch) || batch.size() < batchSize) {
                        return;
                    }
                    after = order.keys().stream().map(key -> key.value().apply(batch.getLast())).toArray();
                }
            }

            @Override
            public boolean customSorted() {
                return order.custom();
            }
        };
    }

    /** The {@code status} filter of the log and its export: blank = all, else PENDING | SENT | FAILED. */
    private static String statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String statusFilter = status.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(statusFilter)) {
            throw new BadRequestException("INVALID_PARAMETER", "Unknown status '" + status + "'");
        }
        return statusFilter;
    }

    private static EmailLogEntry entry(OutboxEmail email) {
        return new EmailLogEntry(email.id(), email.notificationId(), email.toAddress(), email.subject(), email.type(),
                email.status(), email.attempts(), email.lastError(), email.createdAt(), email.sentAt());
    }

    private static Preferences view(EmailPreferences preferences) {
        return new Preferences(new EmailPreferencesView(preferences.orders(), preferences.account(),
                preferences.approvals()));
    }
}
