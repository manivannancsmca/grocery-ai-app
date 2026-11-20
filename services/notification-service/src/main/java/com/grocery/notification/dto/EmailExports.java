package com.grocery.notification.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.notification.dto.EmailDtos.EmailLogEntry;

/**
 * The CSV / Excel / PDF export of the admin email log: the columns of the list's rows (never an e-mail's body or
 * link) and the orders a client may choose.
 */
public final class EmailExports {

    private EmailExports() {
    }

    /**
     * Newest first like the list; a client may sort by recipient, subject, status, attempts or creation time (the
     * page's column keys, all never-null columns). The paths are {@code email_outbox} columns: the log is read with
     * plain JDBC ({@code EmailOutboxRepository.logBatch}), not JPA.
     */
    public static final ExportSort<EmailLogEntry> LOG_SORT = ExportSort.<EmailLogEntry>by(
                    Key.desc("created_at", EmailLogEntry::createdAt), Key.desc("id", EmailLogEntry::id))
            .option("to", "to_address", EmailLogEntry::to)
            .option("subject", "subject", EmailLogEntry::subject)
            .option("status", "status", EmailLogEntry::status)
            .option("attempts", "attempts", EmailLogEntry::attempts)
            .option("when", "created_at", EmailLogEntry::createdAt)
            .build();

    /**
     * {@code GET /api/admin/notifications/emails/export}. The recipient's address is personal, and so is the last
     * SMTP error (servers quote the rejected address in it): only support and operations admins get them
     * ({@link ExportTable#visibleTo}).
     */
    public static final ExportTable<EmailLogEntry> EMAIL_LOG = ExportTable.<EmailLogEntry>named(
                    "email-log", "Email log")
            .text("To", EmailLogEntry::to).personal()
            .text("Subject", EmailLogEntry::subject)
            .label("Type", EmailLogEntry::type)
            .label("Status", EmailLogEntry::status)
            .integer("Attempts", EmailLogEntry::attempts)
            .text("Last error", EmailLogEntry::lastError).personal()
            .dateTime("Created", EmailLogEntry::createdAt)
            .dateTime("Sent", EmailLogEntry::sentAt)
            .build();
}
