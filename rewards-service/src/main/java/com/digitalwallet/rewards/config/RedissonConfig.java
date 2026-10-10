package com.digitalwallet.rewards.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(RewardsProperties properties) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(properties.redisAddress())
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(16);
        return Redisson.create(config);
    }
}
