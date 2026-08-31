package com.goev.state;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

@Configuration
@EnableRedisRepositories(basePackageClasses = {EvLiveStateRepository.class, EvcsLiveStateRepository.class})
@EnableConfigurationProperties(StateProperties.class)
public class RedisConfig {
}
