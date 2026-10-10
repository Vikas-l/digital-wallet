package com.digitalwallet.rewards.outbox;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /** Must join the caller's transaction: the event is only stored if the business change commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, String aggregateType, String aggregateId, String topic, String key, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxRepository.save(new OutboxEvent(eventId, aggregateType, aggregateId, topic, key, payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize outbox event " + eventId, e);
        }
    }
}
