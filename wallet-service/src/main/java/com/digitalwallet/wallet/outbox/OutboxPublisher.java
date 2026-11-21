package com.digitalwallet.wallet.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.digitalwallet.wallet.config.WalletProperties;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relays pending outbox rows to Kafka. Delivery is at-least-once: if the process
 * dies after sending but before committing, the row is sent again, which is why
 * every consumer de-duplicates on eventId.
 *
 * <p>Failure handling:
 * <ul>
 *   <li><b>Per-key blocking:</b> when an event fails, later events with the same key
 *       (the same user) wait, so one user's events stay in order; events of other
 *       users keep flowing (no head-of-line blocking of the whole outbox).</li>
 *   <li><b>Exponential backoff:</b> a failed event is retried after 1s, 2s, 4s ... up to
 *       a cap, instead of every poll.</li>
 *   <li><b>Max attempts:</b> after that the event is marked FAILED and logged at ERROR;
 *       the {@code outbox.events{status="failed"}} gauge is meant for an alert.</li>
 *   <li><b>Broker down:</b> after a few consecutive failures the batch stops early, so a
 *       Kafka outage does not hold the row locks for batch-size x timeout.</li>
 * </ul>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
            WalletProperties properties, MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = properties.outbox().batchSize();
        this.maxAttempts = properties.outbox().maxAttempts();
        this.initialBackoff = properties.outbox().initialBackoff();
        this.maxBackoff = properties.outbox().maxBackoff();
        Gauge.builder("outbox.events", outboxRepository, r -> r.countByStatus(OutboxEvent.Status.PENDING))
                .tag("status", "pending").description("Outbox events waiting to be published")
                .register(meterRegistry);
        Gauge.builder("outbox.events", outboxRepository, r -> r.countByStatus(OutboxEvent.Status.FAILED))
                .tag("status", "failed").description("Outbox events that gave up; alert when > 0")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${digitalwallet.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        Instant now = Instant.now();
        Set<String> blockedKeys = new HashSet<>();
        int consecutiveFailures = 0;

        for (OutboxEvent event : outboxRepository.lockNextBatch(batchSize)) {
            String key = event.getEventKey();
            if (blockedKeys.contains(key)) {
                continue; // an earlier event of this key is not published yet: keep the order
            }
            if (!event.isDueAt(now)) {
                blockedKeys.add(key); // waiting out its backoff; later events of the key wait too
                continue;
            }
            try {
                kafkaTemplate.send(event.getTopic(), key, event.getPayload()).get(10, TimeUnit.SECONDS);
                event.markPublished();
                consecutiveFailures = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException e) {
                blockedKeys.add(key);
                String error = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                event.markFailed(error, maxAttempts, backoff(event.getAttempts() + 1));
                if (event.getStatus() == OutboxEvent.Status.FAILED) {
                    log.error("Outbox event {} FAILED after {} attempts, needs manual requeue: {}",
                            event.getId(), event.getAttempts(), error);
                } else {
                    log.warn("Outbox event {} not published (attempt {}), next try at {}: {}",
                            event.getId(), event.getAttempts(), event.getNextAttemptAt(), error);
                }
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("Kafka looks unavailable, stopping this outbox batch early");
                    return;
                }
            }
        }
    }

    /** 1s, 2s, 4s, 8s ... capped at maxBackoff. */
    Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        Duration delay = initialBackoff.multipliedBy(1L << exponent);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    /** Housekeeping: published rows are only kept for a week for troubleshooting. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgePublished() {
        int deleted = outboxRepository.deletePublishedBefore(OutboxEvent.Status.PUBLISHED,
                Instant.now().minus(Duration.ofDays(7)));
        if (deleted > 0) {
            log.info("Purged {} published outbox events", deleted);
        }
    }
}
