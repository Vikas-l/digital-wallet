package com.digitalwallet.wallet.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.digitalwallet.common.events.RewardGrantedEvent;
import com.digitalwallet.common.events.Topics;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RewardGrantedListener {

    private final ObjectMapper objectMapper;
    private final RewardCreditHandler handler;

    public RewardGrantedListener(ObjectMapper objectMapper, RewardCreditHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(topics = Topics.REWARD_GRANTED, groupId = "wallet-service")
    public void onRewardGranted(String payload) throws JsonProcessingException {
        RewardGrantedEvent event = objectMapper.readValue(payload, RewardGrantedEvent.class);
        if (event.eventId() == null || event.rewardId() == null || event.userId() == null || event.amount() == null) {
            throw new IllegalArgumentException("Incomplete RewardGrantedEvent: " + payload);
        }
        handler.handle(event);
    }
}
