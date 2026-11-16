package com.cse444.agentgate.guard.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for AgentToolLimitConfig (FR6).
 */
@Repository
public interface AgentToolLimitRepository extends JpaRepository<AgentToolLimitConfig, UUID> {

    Optional<AgentToolLimitConfig> findByAgentClientIdAndToolName(String agentClientId, String toolName);
}
