package com.digitalwallet.rewards.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.digitalwallet.common.events.Topics;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    NewTopic rewardGrantedTopic() {
        return TopicBuilder.name(Topics.REWARD_GRANTED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic transactionCompletedDeadLetterTopic() {
        return TopicBuilder.name(Topics.TRANSACTION_COMPLETED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    /** 3 retries 1s apart, then the record goes to "&lt;topic&gt;.DLT". Bad payloads skip retries. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }
}
