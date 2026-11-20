package com.grocery.notification.email;

import com.grocery.notification.email.EmailRenderer.RenderedEmail;
import com.grocery.notification.repository.EmailOutboxRepository;
import com.grocery.notification.repository.EmailOutboxRepository.OutboxEmail;
import com.grocery.notification.service.NotificationMetrics;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends the due emails of {@code email_outbox} (Phase 12 §5); run by {@link EmailSenderSchedule} every
 * {@code poll-interval}, safe on any number of replicas:
 * <ol>
 *   <li>a short transaction takes up to {@code batch-size} due PENDING rows with {@code FOR UPDATE SKIP LOCKED} (rows
 *       another replica holds are skipped) and pushes their due time by {@code lease}, then commits — no database
 *       transaction is open while SMTP is talked to;</li>
 *   <li>each email is sent on its own; success → SENT; failure → tried again after 1 min, 5 min, 30 min
 *       ({@code retry-delays}), and after the last one FAILED; an address SMTP cannot even parse fails at once;</li>
 *   <li>a run that took longer than half the lease gives its unsent rows back (due at once, no attempt counted), so the
 *       lease never runs out under an email still being sent. A replica that dies mid-run leaves its rows to be taken
 *       again when the lease ends.</li>
 * </ol>
 * Each email belongs to one (notification, recipient) row and is sent once: SENT and FAILED rows are never taken
 * again. (SMTP has no idempotency: only a crash between the SMTP server accepting a message and the SENT update can
 * repeat that one email.) Every hand-over is counted in {@code grocery_emails_total{status}}: SENT, or FAILED for each
 * failed attempt ({@link NotificationMetrics}).
 */
@Component
public class EmailSender {

    /** What one run did. */
    public record Result(int sent, int retried, int failed, int released) {
    }

    static final String HEADER_NOTIFICATION = "X-Grocery-Notification-Id";
    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final EmailOutboxRepository outbox;
    private final JavaMailSender mail;
    private final EmailRenderer renderer;
    private final EmailProperties properties;
    private final TransactionTemplate transaction;
    private final NotificationMetrics metrics;
    private final Clock clock;

    public EmailSender(EmailOutboxRepository outbox, JavaMailSender mail, EmailRenderer renderer,
                       EmailProperties properties, PlatformTransactionManager transactionManager,
                       NotificationMetrics metrics, Clock clock) {
        this.outbox = outbox;
        this.mail = mail;
        this.renderer = renderer;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.clock = clock;
    }

    /** One run: takes the due emails and sends them. */
    public Result sendDue() {
        Instant start = clock.instant();
        Duration lease = properties.getLease();
        List<OutboxEmail> claimed = transaction.execute(status ->
                outbox.claimDue(start, Math.max(1, properties.getBatchSize()), start.plus(lease)));
        if (claimed == null || claimed.isEmpty()) {
            return new Result(0, 0, 0, 0);
        }
        Instant deadline = start.plus(lease.dividedBy(2));
        int sent = 0;
        int retried = 0;
        int failed = 0;
        int released = 0;
        for (OutboxEmail email : claimed) {
            Instant now = clock.instant();
            if (now.isAfter(deadline)) {
                outbox.release(email.id(), now);
                released++;
                continue;
            }
            int attempt = email.attempts() + 1;
            try {
                mail.send(message(email));
                metrics.emailSent();
                outbox.markSent(email.id(), attempt, clock.instant());
                sent++;
            } catch (MailPreparationException e) {
                // the message cannot be built (an address SMTP cannot parse): trying again changes nothing
                metrics.emailFailed();
                outbox.markFailed(email.id(), attempt, describe(e), clock.instant());
                failed++;
                log.warn("Email {} ({} to {}) cannot be sent: {}", email.id(), email.type(), email.toAddress(),
                        describe(e));
            } catch (RuntimeException e) {         // MailSendException, MailAuthenticationException, …
                metrics.emailFailed();
                if (attempt > properties.getRetryDelays().size()) {
                    outbox.markFailed(email.id(), attempt, describe(e), clock.instant());
                    failed++;
                    log.warn("Email {} ({} to {}) FAILED after {} attempts: {}", email.id(), email.type(),
                            email.toAddress(), attempt, describe(e));
                } else {
                    Instant next = clock.instant().plus(properties.getRetryDelays().get(attempt - 1));
                    outbox.markRetry(email.id(), attempt, next, describe(e));
                    retried++;
                    log.info("Email {} ({} to {}) not sent (attempt {}), next try at {}: {}", email.id(),
                            email.type(), email.toAddress(), attempt, next, describe(e));
                }
            }
        }
        if (sent + failed + retried + released > 0) {
            log.debug("Email run: {} sent, {} to retry, {} failed, {} given back", sent, retried, failed, released);
        }
        return new Result(sent, retried, failed, released);
    }

    /** The MIME message: From, To (with the name), Subject, a text and an HTML alternative. */
    MimeMessage message(OutboxEmail email) {
        RenderedEmail rendered = renderer.render(email.type(), email.subject(), email.body(), email.link());
        MimeMessage message = mail.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getFrom());
            if (email.toName() == null || email.toName().isBlank()) {
                helper.setTo(email.toAddress());
            } else {
                helper.setTo(new jakarta.mail.internet.InternetAddress(email.toAddress(),
                        EmailRenderer.oneLine(email.toName()), StandardCharsets.UTF_8.name()));
            }
            helper.setSubject(rendered.subject());
            helper.setText(rendered.text(), rendered.html());
            message.setHeader(HEADER_NOTIFICATION, email.notificationId().toString());
            return message;
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Cannot build the email: " + e.getMessage(), e);
        }
    }

    private static String describe(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (root != e && root.getMessage() != null && !message.contains(root.getMessage())) {
            message += " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
        }
        return message;
    }
}
