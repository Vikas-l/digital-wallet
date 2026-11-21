package com.digitalwallet.wallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.digitalwallet.wallet.config.WalletProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(repository, kafka, properties(3),
            new SimpleMeterRegistry());

    @Test
    void failureBlocksOnlyLaterEventsOfTheSameKey() {
        OutboxEvent aliceFirst = event("alice", "a1");
        OutboxEvent aliceSecond = event("alice", "a2");
        OutboxEvent bob = event("bob", "b1");
        when(repository.lockNextBatch(anyInt())).thenReturn(List.of(aliceFirst, aliceSecond, bob));
        when(kafka.send(anyString(), eq("alice"), eq("a1"))).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("broker rejected")));
        when(kafka.send(anyString(), eq("bob"), eq("b1"))).thenReturn(ok());

        publisher.publishPending();

        assertThat(aliceFirst.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(aliceFirst.getAttempts()).isEqualTo(1);
        assertThat(aliceFirst.getNextAttemptAt()).isNotNull();
        verify(kafka, never()).send(anyString(), eq("alice"), eq("a2")); // order kept for alice
        assertThat(bob.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED); // bob not blocked
    }

    @Test
    void eventIsMarkedFailedAfterMaxAttempts() {
        OutboxEvent event = event("alice", "a1");
        event.markFailed("earlier", 3, Duration.ZERO);
        event.markFailed("earlier", 3, Duration.ZERO);
        when(repository.lockNextBatch(anyInt())).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("still failing")));

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.FAILED);
        assertThat(event.getAttempts()).isEqualTo(3);
    }

    @Test
    void backoffDoublesUpToTheCap() {
        assertThat(publisher.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(publisher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(publisher.backoff(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(publisher.backoff(30)).isEqualTo(Duration.ofMinutes(5));
    }

    private static OutboxEvent event(String key, String payload) {
        return new OutboxEvent(UUID.randomUUID(), "Transaction", "tx", "topic", key, payload);
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static WalletProperties properties(int maxAttempts) {
        return new WalletProperties(null, null, "redis://localhost:6379", "INR", null,
                new WalletProperties.Outbox(100, maxAttempts, Duration.ofSeconds(1), Duration.ofMinutes(5)),
                null, null, null);
    }
}
