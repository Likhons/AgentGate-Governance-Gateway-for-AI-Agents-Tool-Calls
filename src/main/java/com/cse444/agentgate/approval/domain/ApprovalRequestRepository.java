package com.cse444.agentgate.approval.domain;

import com.cse444.agentgate.shared.domain.ApprovalStateType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for ApprovalRequest entities (FR4, FR8, C3 pagination).
 */
@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {

    Page<ApprovalRequest> findAllByStateOrderByCreatedAtDesc(ApprovalStateType state, Pageable pageable);

    List<ApprovalRequest> findAllByStateAndExpiresAtBefore(ApprovalStateType state, Instant now);

    List<ApprovalRequest> findAllByStateAndAgentClientId(ApprovalStateType state, String agentClientId);

    List<ApprovalRequest> findAllByStateAndToolName(ApprovalStateType state, String toolName);

    List<ApprovalRequest> findAllByStateAndWriteOperationTrue(ApprovalStateType state);
}
