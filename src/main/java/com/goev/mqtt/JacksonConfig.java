package com.goev.mqtt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Spring Boot's JacksonAutoConfiguration needs Jackson2ObjectMapperBuilder,
 * which lives in spring-web — not on this classpath since there's no web
 * starter. Defining the ObjectMapper bean directly here is simpler than
 * pulling in a web dependency this app doesn't otherwise need.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
