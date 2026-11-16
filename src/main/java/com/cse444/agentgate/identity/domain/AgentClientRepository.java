package com.cse444.agentgate.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for AgentClient entities (FR2, FR8).
 */
@Repository
public interface AgentClientRepository extends JpaRepository<AgentClient, UUID> {

    Optional<AgentClient> findByClientId(String clientId);

    boolean existsByClientId(String clientId);
}
