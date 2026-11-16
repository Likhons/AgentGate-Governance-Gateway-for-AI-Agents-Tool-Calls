package com.cse444.agentgate.audit.service;

import com.cse444.agentgate.audit.domain.AuditLogEntry;
import com.cse444.agentgate.audit.domain.AuditLogRepository;
import com.cse444.agentgate.audit.dto.AuditChainVerificationReport;
import com.cse444.agentgate.audit.dto.AuditLogResponse;
import com.cse444.agentgate.shared.event.ToolCallAuditRecordedEvent;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tamper-Evident Audit Trail Service (FR7, QA3).
 * Design Pattern: Observer Pattern via @TransactionalEventListener.
 * Listens for ToolCallAuditRecordedEvent, chains each entry to the previous entry's hash,
 * supports multi-attribute search & export, and verifies chain integrity on demand.
 */
@Service
public class AuditTrailService {

    private final AuditLogRepository auditRepository;
    private final AuditHashChainCalculator hashCalculator;

    public AuditTrailService(
            AuditLogRepository auditRepository,
            AuditHashChainCalculator hashCalculator
    ) {
        this.auditRepository = auditRepository;
        this.hashCalculator = hashCalculator;
    }

    /**
     * FR7: Records every tool call via @TransactionalEventListener.
     * Synchronized hash-chain append ensures strict sequential linking of previousHash -> entryHash.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized AuditLogResponse recordAuditEvent(ToolCallAuditRecordedEvent event) {
        Optional<AuditLogEntry> latest = auditRepository.findTopByOrderBySequenceNumberDesc();
        long nextSeq = latest.map(e -> e.getSequenceNumber() + 1L).orElse(1L);
        String previousHash = latest.map(AuditLogEntry::getEntryHash).orElse(AuditLogEntry.GENESIS_HASH);

        // Truncate timestamp to milliseconds so PostgreSQL TIMESTAMPTZ round-trip is identical
        Instant truncatedOccurredAt = (event.occurredAt() != null ? event.occurredAt() : Instant.now())
                .truncatedTo(ChronoUnit.MILLIS);
        UUID entryId = event.eventId() != null ? event.eventId() : UUID.randomUUID();

        String entryHash = hashCalculator.computeEntryHash(
                nextSeq,
                entryId,
                event.traceId(),
                event.agentClientId(),
                event.delegatedUserId(),
                event.toolName(),
                event.redactedArgumentsJson(),
                event.policyRuleName(),
                event.policyRuleVersion(),
                event.approvalId(),
                event.approvalState(),
                event.outcome(),
                event.latencyMs(),
                truncatedOccurredAt,
                previousHash
        );

        AuditLogEntry entry = new AuditLogEntry(
                entryId,
                nextSeq,
                event.traceId(),
                event.agentClientId(),
                event.delegatedUserId(),
                event.toolName(),
                event.redactedArgumentsJson(),
                event.policyRuleName(),
                event.policyRuleVersion(),
                event.approvalId(),
                event.approvalState(),
                event.outcome(),
                event.latencyMs(),
                truncatedOccurredAt,
                previousHash,
                entryHash
        );

        return AuditLogResponse.from(auditRepository.saveAndFlush(entry));
    }

    /**
     * QA3 Verify Command: Recomputes every entry's hash and verifies previousHash linkage.
     * Editing any column of any stored audit row or deleting a row is immediately detected.
     */
    @Transactional(readOnly = true)
    public AuditChainVerificationReport verifyChainIntegrity() {
        List<AuditLogEntry> allEntries = auditRepository.findAllByOrderBySequenceNumberAsc();
        String expectedPreviousHash = AuditLogEntry.GENESIS_HASH;
        long expectedSeq = 1L;

        for (AuditLogEntry entry : allEntries) {
            if (entry.getSequenceNumber() != expectedSeq) {
                return new AuditChainVerificationReport(
                        false,
                        expectedSeq - 1,
                        entry.getSequenceNumber(),
                        entry.getId(),
                        "Sequence gap detected: expected sequence " + expectedSeq + " but found " + entry.getSequenceNumber(),
                        Instant.now()
                );
            }

            if (!expectedPreviousHash.equals(entry.getPreviousHash())) {
                return new AuditChainVerificationReport(
                        false,
                        expectedSeq - 1,
                        entry.getSequenceNumber(),
                        entry.getId(),
                        "Broken previous_hash link at sequence " + entry.getSequenceNumber(),
                        Instant.now()
                );
            }

            Instant truncatedOccurredAt = entry.getOccurredAt().truncatedTo(ChronoUnit.MILLIS);
            String recomputedHash = hashCalculator.computeEntryHash(
                    entry.getSequenceNumber(),
                    entry.getId(),
                    entry.getTraceId(),
                    entry.getAgentClientId(),
                    entry.getDelegatedUserId(),
                    entry.getToolName(),
                    entry.getRedactedArgumentsJson(),
                    entry.getPolicyRuleName(),
                    entry.getPolicyRuleVersion(),
                    entry.getApprovalId(),
                    entry.getApprovalState(),
                    entry.getOutcome(),
                    entry.getLatencyMs(),
                    truncatedOccurredAt,
                    entry.getPreviousHash()
            );

            if (!recomputedHash.equals(entry.getEntryHash())) {
                return new AuditChainVerificationReport(
                        false,
                        expectedSeq - 1,
                        entry.getSequenceNumber(),
                        entry.getId(),
                        "Tampering detected at sequence " + entry.getSequenceNumber()
                                + " (id=" + entry.getId() + "): stored entry_hash does not match recomputed HMAC-SHA256 digest",
                        Instant.now()
                );
            }

            expectedPreviousHash = entry.getEntryHash();
            expectedSeq++;
        }

        return new AuditChainVerificationReport(
                true,
                allEntries.size(),
                null,
                null,
                "All hash-chained audit records verified intact",
                Instant.now()
        );
    }

    /**
     * FR7 & QA3: Multi-criteria paginated search so an auditor finds any call in seconds.
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> searchAuditLogs(
            String agentClientId,
            String delegatedUserId,
            String toolName,
            String outcome,
            String policyRuleName,
            Pageable pageable
    ) {
        Specification<AuditLogEntry> spec = buildSearchSpecification(
                agentClientId, delegatedUserId, toolName, outcome, policyRuleName);
        return auditRepository.findAll(spec, pageable).map(AuditLogResponse::from);
    }

    /**
     * FR7: Exports matching audit records as CSV for compliance reporting.
     */
    @Transactional(readOnly = true)
    public String exportAuditLogsAsCsv(
            String agentClientId,
            String delegatedUserId,
            String toolName,
            String outcome
    ) {
        Specification<AuditLogEntry> spec = buildSearchSpecification(
                agentClientId, delegatedUserId, toolName, outcome, null);
        List<AuditLogEntry> rows = auditRepository.findAll(spec);

        StringBuilder csv = new StringBuilder();
        csv.append("sequence_number,id,trace_id,agent_client_id,delegated_user_id,tool_name,policy_rule_name,policy_rule_version,approval_id,approval_state,outcome,latency_ms,occurred_at,previous_hash,entry_hash\n");
        for (AuditLogEntry r : rows) {
            csv.append(r.getSequenceNumber()).append(',')
                    .append(r.getId()).append(',')
                    .append(r.getTraceId()).append(',')
                    .append(r.getAgentClientId()).append(',')
                    .append(r.getDelegatedUserId() != null ? r.getDelegatedUserId() : "").append(',')
                    .append(r.getToolName()).append(',')
                    .append(r.getPolicyRuleName()).append(',')
                    .append(r.getPolicyRuleVersion()).append(',')
                    .append(r.getApprovalId() != null ? r.getApprovalId() : "").append(',')
                    .append(r.getApprovalState() != null ? r.getApprovalState() : "").append(',')
                    .append(r.getOutcome()).append(',')
                    .append(r.getLatencyMs()).append(',')
                    .append(r.getOccurredAt()).append(',')
                    .append(r.getPreviousHash()).append(',')
                    .append(r.getEntryHash()).append('\n');
        }
        return csv.toString();
    }

    private Specification<AuditLogEntry> buildSearchSpecification(
            String agentClientId,
            String delegatedUserId,
            String toolName,
            String outcome,
            String policyRuleName
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (agentClientId != null && !agentClientId.isBlank()) {
                predicates.add(cb.equal(root.get("agentClientId"), agentClientId));
            }
            if (delegatedUserId != null && !delegatedUserId.isBlank()) {
                predicates.add(cb.equal(root.get("delegatedUserId"), delegatedUserId));
            }
            if (toolName != null && !toolName.isBlank()) {
                predicates.add(cb.equal(root.get("toolName"), toolName));
            }
            if (outcome != null && !outcome.isBlank()) {
                predicates.add(cb.equal(root.get("outcome"), outcome));
            }
            if (policyRuleName != null && !policyRuleName.isBlank()) {
                predicates.add(cb.equal(root.get("policyRuleName"), policyRuleName));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
