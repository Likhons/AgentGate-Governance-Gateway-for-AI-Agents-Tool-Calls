package com.cse444.agentgate.audit.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity representing an immutable, cryptographically hash-chained Audit Log record (FR7, QA3).
 * Editing any stored column breaks the deterministic SHA-256 hash chain and is detected by verifyChainIntegrity().
 */
@Entity
@Table(name = "audit_log_entries")
public class AuditLogEntry {

    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sequence_number", nullable = false, unique = true)
    private long sequenceNumber;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "agent_client_id", nullable = false, length = 100)
    private String agentClientId;

    @Column(name = "delegated_user_id", length = 120)
    private String delegatedUserId;

    @Column(name = "tool_name", nullable = false, length = 120)
    private String toolName;

    @Column(name = "redacted_arguments_json", nullable = false, columnDefinition = "TEXT")
    private String redactedArgumentsJson;

    @Column(name = "policy_rule_name", nullable = false, length = 140)
    private String policyRuleName;

    @Column(name = "policy_rule_version", nullable = false)
    private int policyRuleVersion;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "approval_state", length = 32)
    private String approvalState;

    @Column(name = "outcome", nullable = false, length = 40)
    private String outcome;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    @Column(name = "entry_hash", nullable = false, length = 64)
    private String entryHash;

    protected AuditLogEntry() {}

    public AuditLogEntry(
            UUID id,
            long sequenceNumber,
            String traceId,
            String agentClientId,
            String delegatedUserId,
            String toolName,
            String redactedArgumentsJson,
            String policyRuleName,
            int policyRuleVersion,
            UUID approvalId,
            String approvalState,
            String outcome,
            long latencyMs,
            Instant occurredAt,
            String previousHash,
            String entryHash
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.sequenceNumber = sequenceNumber;
        this.traceId = traceId;
        this.agentClientId = agentClientId;
        this.delegatedUserId = delegatedUserId;
        this.toolName = toolName;
        this.redactedArgumentsJson = redactedArgumentsJson;
        this.policyRuleName = policyRuleName;
        this.policyRuleVersion = policyRuleVersion;
        this.approvalId = approvalId;
        this.approvalState = approvalState;
        this.outcome = outcome;
        this.latencyMs = latencyMs;
        this.occurredAt = occurredAt;
        this.previousHash = previousHash;
        this.entryHash = entryHash;
    }

    public UUID getId() { return id; }
    public long getSequenceNumber() { return sequenceNumber; }
    public String getTraceId() { return traceId; }
    public String getAgentClientId() { return agentClientId; }
    public String getDelegatedUserId() { return delegatedUserId; }
    public String getToolName() { return toolName; }
    public String getRedactedArgumentsJson() { return redactedArgumentsJson; }
    public String getPolicyRuleName() { return policyRuleName; }
    public int getPolicyRuleVersion() { return policyRuleVersion; }
    public UUID getApprovalId() { return approvalId; }
    public String getApprovalState() { return approvalState; }
    public String getOutcome() { return outcome; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getPreviousHash() { return previousHash; }
    public String getEntryHash() { return entryHash; }
}
