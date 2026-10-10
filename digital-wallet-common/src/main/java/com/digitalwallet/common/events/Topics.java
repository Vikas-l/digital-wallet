package com.digitalwallet.common.events;

/** Kafka topic names shared by producers and consumers. */
public final class Topics {

    public static final String TRANSACTION_COMPLETED = "digitalwallet.transaction-completed";
    public static final String REWARD_GRANTED = "digitalwallet.reward-granted";

    private Topics() {
    }
}
