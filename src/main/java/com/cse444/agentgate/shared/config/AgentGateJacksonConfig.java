package com.cse444.agentgate.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a Jackson 2 ObjectMapper bean for libraries (OpenAPI swagger-parser,
 * networknt json-schema-validator) and services when running on Spring Boot 4 / Jackson 3.
 */
@Configuration
public class AgentGateJacksonConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
