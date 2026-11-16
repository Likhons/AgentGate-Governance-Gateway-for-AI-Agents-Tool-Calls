package com.cse444.agentgate.policy.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for versioned PolicyRule entities (FR3, FR9).
 */
@Repository
public interface PolicyRuleRepository extends JpaRepository<PolicyRule, UUID> {

    List<PolicyRule> findAllByActiveTrueOrderByPriorityAscVersionDesc();

    List<PolicyRule> findAllByRuleNameAndActiveTrue(String ruleName);

    @Query("SELECT COALESCE(MAX(p.version), 0) FROM PolicyRule p WHERE p.ruleName = :ruleName")
    int findMaxVersionByRuleName(String ruleName);

    Optional<PolicyRule> findByRuleNameAndVersion(String ruleName, int version);

    Page<PolicyRule> findAllByOrderByPriorityAscVersionDesc(Pageable pageable);
}
