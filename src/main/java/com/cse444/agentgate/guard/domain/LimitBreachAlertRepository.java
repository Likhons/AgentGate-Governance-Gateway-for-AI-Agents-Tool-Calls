package com.cse444.agentgate.guard.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Spring Data JPA Repository for raised limit-breach alerts (FR6).
 */
@Repository
public interface LimitBreachAlertRepository extends JpaRepository<LimitBreachAlert, UUID> {

    Page<LimitBreachAlert> findAllByOrderByRaisedAtDesc(Pageable pageable);
}
