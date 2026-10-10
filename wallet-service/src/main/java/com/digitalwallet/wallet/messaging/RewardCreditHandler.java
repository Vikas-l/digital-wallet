package com.digitalwallet.wallet.messaging;

import com.digitalwallet.common.events.RewardGrantedEvent;
import com.digitalwallet.wallet.wallet.WalletService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RewardCreditHandler {

    private static final Logger log = LoggerFactory.getLogger(RewardCreditHandler.class);

    private final ProcessedEventRepository processedEventRepository;
    private final WalletService walletService;

    public RewardCreditHandler(ProcessedEventRepository processedEventRepository, WalletService walletService) {
        this.processedEventRepository = processedEventRepository;
        this.walletService = walletService;
    }

    /**
     * The "processed" marker and the wallet credit commit together, so the event is
     * applied exactly once even though Kafka may deliver it more than once.
     */
    @Transactional
    public void handle(RewardGrantedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Skipping duplicate reward event {}", event.eventId());
            return;
        }
        processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
        walletService.creditReward(event);
        log.info("Credited reward {} of {} to user {}", event.rewardId(), event.amount(), event.userId());
    }
}
