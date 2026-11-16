package com.cse444.agentgate.registry.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for reading/updating global kill-switch state (FR8).
 */
@Repository
public interface GlobalKillSwitchRepository extends JpaRepository<GlobalKillSwitchState, String> {
}
