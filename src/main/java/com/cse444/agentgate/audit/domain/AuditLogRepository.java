package com.cse444.agentgate.audit.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for tamper-evident AuditLogEntry entities (FR7, QA3).
 */
@Repository
public interface AuditLogRepository
        extends JpaRepository<AuditLogEntry, UUID>, JpaSpecificationExecutor<AuditLogEntry> {

    Optional<AuditLogEntry> findTopByOrderBySequenceNumberDesc();

    List<AuditLogEntry> findAllByOrderBySequenceNumberAsc();

    Page<AuditLogEntry> findAll(Specification<AuditLogEntry> spec, Pageable pageable);
}
