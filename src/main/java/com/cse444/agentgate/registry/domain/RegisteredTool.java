package com.cse444.agentgate.registry.domain;

import com.cse444.agentgate.shared.domain.RiskLevel;
import com.cse444.agentgate.shared.domain.ToolStatus;
import com.cse444.agentgate.shared.domain.ToolType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity representing a registered downstream MCP or REST Tool (FR1, FR8, FR10).
 */
@Entity
@Table(name = "registered_tools")
public class RegisteredTool {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 120)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "input_schema", nullable = false, columnDefinition = "TEXT")
    private String inputSchema;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @Column(name = "owning_team", nullable = false, length = 100)
    private String owningTeam;

    @Enumerated(EnumType.STRING)
    @Column(name = "tool_type", nullable = false, length = 20)
    private ToolType toolType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ToolStatus status;

    @Column(name = "write_operation", nullable = false)
    private boolean writeOperation;

    @Column(name = "downstream_url", nullable = false, length = 512)
    private String downstreamUrl;

    @Column(name = "http_method", nullable = false, length = 16)
    private String httpMethod;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    /**
     * Administrator-configured approver webhook for high-risk calls to this tool (FR4).
     * Never taken from the calling agent, so a prompt-injected agent cannot redirect approval traffic.
     */
    @Column(name = "approval_webhook_url", length = 512)
    private String approvalWebhookUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected RegisteredTool() {
        // Required by JPA
    }

    public RegisteredTool(
            UUID id,
            String name,
            String description,
            String inputSchema,
            RiskLevel riskLevel,
            String owningTeam,
            ToolType toolType,
            boolean writeOperation,
            String downstreamUrl,
            String httpMethod,
            int timeoutMs
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.riskLevel = riskLevel;
        this.owningTeam = owningTeam;
        this.toolType = toolType;
        this.status = ToolStatus.ENABLED;
        this.writeOperation = writeOperation;
        this.downstreamUrl = downstreamUrl;
        this.httpMethod = httpMethod != null ? httpMethod.toUpperCase() : "POST";
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 3000;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void disable() {
        this.status = ToolStatus.DISABLED;
        this.updatedAt = Instant.now();
    }

    public void enable() {
        this.status = ToolStatus.ENABLED;
        this.updatedAt = Instant.now();
    }

    public void updateMetadata(
            String description,
            String inputSchema,
            RiskLevel riskLevel,
            String owningTeam,
            boolean writeOperation,
            String downstreamUrl,
            String httpMethod,
            int timeoutMs
    ) {
        this.description = description;
        this.inputSchema = inputSchema;
        this.riskLevel = riskLevel;
        this.owningTeam = owningTeam;
        this.writeOperation = writeOperation;
        this.downstreamUrl = downstreamUrl;
        this.httpMethod = httpMethod.toUpperCase();
        this.timeoutMs = timeoutMs;
        this.updatedAt = Instant.now();
    }

    public void changeApprovalWebhookUrl(String approvalWebhookUrl) {
        this.approvalWebhookUrl = (approvalWebhookUrl == null || approvalWebhookUrl.isBlank())
                ? null
                : approvalWebhookUrl.trim();
        this.updatedAt = Instant.now();
    }

    public boolean isEnabled() {
        return this.status == ToolStatus.ENABLED;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getOwningTeam() {
        return owningTeam;
    }

    public ToolType getToolType() {
        return toolType;
    }

    public ToolStatus getStatus() {
        return status;
    }

    public boolean isWriteOperation() {
        return writeOperation;
    }

    public String getDownstreamUrl() {
        return downstreamUrl;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public String getApprovalWebhookUrl() {
        return approvalWebhookUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
