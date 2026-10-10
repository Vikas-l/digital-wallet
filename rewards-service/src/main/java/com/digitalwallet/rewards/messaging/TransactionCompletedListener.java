package com.digitalwallet.rewards.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.digitalwallet.common.events.Topics;
import com.digitalwallet.common.events.TransactionCompletedEvent;
import com.digitalwallet.rewards.reward.RewardProcessor;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionCompletedListener {

    private final ObjectMapper objectMapper;
    private final RewardProcessor rewardProcessor;

    public TransactionCompletedListener(ObjectMapper objectMapper, RewardProcessor rewardProcessor) {
        this.objectMapper = objectMapper;
        this.rewardProcessor = rewardProcessor;
    }

    @KafkaListener(topics = Topics.TRANSACTION_COMPLETED, groupId = "rewards-service")
    public void onTransactionCompleted(String payload) throws JsonProcessingException {
        TransactionCompletedEvent event = objectMapper.readValue(payload, TransactionCompletedEvent.class);
        if (event.eventId() == null || event.transactionId() == null || event.type() == null
                || event.userId() == null || event.amount() == null || event.occurredAt() == null) {
            throw new IllegalArgumentException("Incomplete TransactionCompletedEvent: " + payload);
        }
        rewardProcessor.process(event);
    }
}
