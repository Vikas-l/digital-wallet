package com.digitalwallet.rewards.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.digitalwallet.rewards.config.RewardsProperties;

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
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
            RewardsProperties properties) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = properties.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${digitalwallet.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.lockNextBatch(batchSize);
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(10, TimeUnit.SECONDS);
                event.markPublished();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.markFailed("interrupted");
                return;
            } catch (ExecutionException | TimeoutException e) {
                event.markFailed(e.getMessage());
                log.warn("Outbox event {} not published (attempt {}): {}", event.getId(), event.getAttempts(),
                        e.getMessage());
                // stop here so later events for the same key are not published out of order
                return;
            }
        }
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
