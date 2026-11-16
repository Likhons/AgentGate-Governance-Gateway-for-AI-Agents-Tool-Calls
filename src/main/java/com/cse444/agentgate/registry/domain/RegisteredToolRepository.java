package com.cse444.agentgate.registry.domain;

import com.cse444.agentgate.shared.domain.RiskLevel;
import com.cse444.agentgate.shared.domain.ToolStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for RegisteredTool entities (FR1, FR8, C3 pagination).
 */
@Repository
public interface RegisteredToolRepository extends JpaRepository<RegisteredTool, UUID> {

    Optional<RegisteredTool> findByName(String name);

    boolean existsByName(String name);

    List<RegisteredTool> findAllByStatusOrderByNameAsc(ToolStatus status);

    Page<RegisteredTool> findAllByStatus(ToolStatus status, Pageable pageable);

    Page<RegisteredTool> findAllByRiskLevel(RiskLevel riskLevel, Pageable pageable);
}
