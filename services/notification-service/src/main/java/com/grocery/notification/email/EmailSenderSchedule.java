package com.grocery.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Runs the {@link EmailSender} every {@code grocery.notification.email.poll-interval} (fixed delay) while the email
 * channel is {@code enabled} and {@code sender-enabled}. Several replicas run it at the same time safely (SKIP LOCKED).
 */
@Component
@ConditionalOnProperty(prefix = "grocery.notification.email", name = "enabled", havingValue = "true")
public class EmailSenderSchedule implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderSchedule.class);

    private final EmailSender sender;
    private final EmailProperties properties;

    public EmailSenderSchedule(EmailSender sender, EmailProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        Duration interval = properties.getPollInterval() == null || properties.getPollInterval().isNegative()
                || properties.getPollInterval().isZero() ? Duration.ofSeconds(5) : properties.getPollInterval();
        registrar.addFixedDelayTask(new FixedDelayTask(this::run, interval, interval));
    }

    public void run() {
        if (!properties.isSenderEnabled()) {
            return;
        }
        try {
            EmailSender.Result result;
            do {
                result = sender.sendDue();       // a full batch all sent: there may be a backlog, go on at once
            } while (result.sent() >= Math.max(1, properties.getBatchSize()));
        } catch (RuntimeException e) {
            // database down: the rows stay due and the next run tries again
            log.warn("Email sender run failed: {}", e.toString());
        }
    }
}
