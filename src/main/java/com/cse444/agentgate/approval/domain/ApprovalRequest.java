package com.cse444.agentgate.approval.domain;

import com.cse444.agentgate.approval.state.ApprovalLifecycleState;
import com.cse444.agentgate.shared.domain.ApprovalExecutionStatus;
import com.cse444.agentgate.shared.domain.ApprovalStateType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity representing a pending or finalized Human Approval Request for a high-risk tool call (FR4, FR8).
 * Delegates all lifecycle transitions to ApprovalLifecycleState (State Pattern).
 */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agent_client_id", nullable = false, length = 100)
    private String agentClientId;

    @Column(name = "delegated_user_id", length = 120)
    private String delegatedUserId;

    @Column(name = "tool_name", nullable = false, length = 120)
    private String toolName;

    @Column(name = "write_operation", nullable = false)
    private boolean writeOperation;

    @Column(name = "original_arguments_json", nullable = false, columnDefinition = "TEXT")
    private String originalArgumentsJson;

    @Column(name = "effective_arguments_json", nullable = false, columnDefinition = "TEXT")
    private String effectiveArgumentsJson;

    @Column(name = "policy_rule_name", nullable = false, length = 140)
    private String policyRuleName;

    @Column(name = "policy_rule_version", nullable = false)
    private int policyRuleVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ApprovalStateType state;

    @Column(name = "approver_id", length = 120)
    private String approverId;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Column(name = "execution_result_payload", columnDefinition = "TEXT")
    private String executionResultPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", length = 16)
    private ApprovalExecutionStatus executionStatus;

    @Column(name = "webhook_url", length = 512)
    private String webhookUrl;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ApprovalRequest() {}

    public ApprovalRequest(
            UUID id,
            String agentClientId,
            String delegatedUserId,
            String toolName,
            boolean writeOperation,
            String originalArgumentsJson,
            String policyRuleName,
            int policyRuleVersion,
            String webhookUrl,
            Instant expiresAt
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.agentClientId = agentClientId;
        this.delegatedUserId = delegatedUserId;
        this.toolName = toolName;
        this.writeOperation = writeOperation;
        this.originalArgumentsJson = originalArgumentsJson;
        this.effectiveArgumentsJson = originalArgumentsJson;
        this.policyRuleName = policyRuleName;
        this.policyRuleVersion = policyRuleVersion;
        this.state = ApprovalStateType.PENDING;
        this.webhookUrl = webhookUrl;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public ApprovalLifecycleState currentStateObject() {
        return ApprovalLifecycleState.fromType(this.state);
    }

    public void approve(String approverId, String note) {
        currentStateObject().approve(this, approverId, note);
    }

    public void approveWithEditedArguments(String approverId, String editedArgsJson, String note) {
        currentStateObject().approveWithEditedArguments(this, approverId, editedArgsJson, note);
    }

    public void reject(String approverId, String reason) {
        currentStateObject().reject(this, approverId, reason);
    }

    public void timeout() {
        currentStateObject().timeout(this);
    }

    public void cancelByKillSwitch(String reason) {
        currentStateObject().cancelByKillSwitch(this, reason);
    }

    public void applyTransition(
            ApprovalStateType nextState,
            String approverId,
            String effectiveArgsJson,
            String note
    ) {
        this.state = nextState;
        this.approverId = approverId;
        this.effectiveArgumentsJson = effectiveArgsJson;
        this.decisionNote = note;
        this.decidedAt = Instant.now();
    }

    public void recordExecutionSucceeded(String redactedPayload) {
        this.executionResultPayload = redactedPayload;
        this.executionStatus = ApprovalExecutionStatus.SUCCEEDED;
    }

    public void recordExecutionFailed(String failurePayload) {
        this.executionResultPayload = failurePayload;
        this.executionStatus = ApprovalExecutionStatus.FAILED;
    }

    public UUID getId() { return id; }
    public String getAgentClientId() { return agentClientId; }
    public String getDelegatedUserId() { return delegatedUserId; }
    public String getToolName() { return toolName; }
    public boolean isWriteOperation() { return writeOperation; }
    public String getOriginalArgumentsJson() { return originalArgumentsJson; }
    public String getEffectiveArgumentsJson() { return effectiveArgumentsJson; }
    public String getPolicyRuleName() { return policyRuleName; }
    public int getPolicyRuleVersion() { return policyRuleVersion; }
    public ApprovalStateType getState() { return state; }
    public String getApproverId() { return approverId; }
    public String getDecisionNote() { return decisionNote; }
    public String getExecutionResultPayload() { return executionResultPayload; }
    public ApprovalExecutionStatus getExecutionStatus() { return executionStatus; }
    public String getWebhookUrl() { return webhookUrl; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
}
