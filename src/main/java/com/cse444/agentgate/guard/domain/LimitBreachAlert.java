package com.cse444.agentgate.guard.domain;

import com.cse444.agentgate.shared.event.LimitBreachAlertEvent.BreachType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A raised limit-breach alert (FR6): an agent hit its per-minute rate limit, daily quota or hourly
 * write blast-radius cap on a tool. Recorded once per agent, tool and breach type per cooldown window.
 */
@Entity
@Table(name = "limit_breach_alerts")
public class LimitBreachAlert {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agent_client_id", nullable = false, length = 100)
    private String agentClientId;

    @Column(name = "delegated_user_id", length = 120)
    private String delegatedUserId;

    @Column(name = "tool_name", nullable = false, length = 120)
    private String toolName;

    @Enumerated(EnumType.STRING)
    @Column(name = "breach_type", nullable = false, length = 40)
    private BreachType breachType;

    @Column(name = "configured_limit", nullable = false)
    private int configuredLimit;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "webhook_url", length = 512)
    private String webhookUrl;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    protected LimitBreachAlert() {
        // Required by JPA
    }

    public LimitBreachAlert(
            String agentClientId,
            String delegatedUserId,
            String toolName,
            BreachType breachType,
            int configuredLimit,
            String message,
            String webhookUrl,
            Instant raisedAt
    ) {
        this.id = UUID.randomUUID();
        this.agentClientId = agentClientId;
        this.delegatedUserId = delegatedUserId;
        this.toolName = toolName;
        this.breachType = breachType;
        this.configuredLimit = configuredLimit;
        this.message = message;
        this.webhookUrl = webhookUrl;
        this.raisedAt = raisedAt;
    }

    public UUID getId() { return id; }
    public String getAgentClientId() { return agentClientId; }
    public String getDelegatedUserId() { return delegatedUserId; }
    public String getToolName() { return toolName; }
    public BreachType getBreachType() { return breachType; }
    public int getConfiguredLimit() { return configuredLimit; }
    public String getMessage() { return message; }
    public String getWebhookUrl() { return webhookUrl; }
    public Instant getRaisedAt() { return raisedAt; }
}
