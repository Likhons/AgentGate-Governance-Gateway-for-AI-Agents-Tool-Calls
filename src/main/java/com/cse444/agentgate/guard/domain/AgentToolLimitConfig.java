package com.cse444.agentgate.guard.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-agent and per-tool Rate Limit, Daily Quota, and Write Blast-Radius Cap configuration (FR6).
 */
@Entity
@Table(
        name = "agent_tool_limits",
        uniqueConstraints = @UniqueConstraint(name = "uq_agent_tool_limit", columnNames = {"agent_client_id", "tool_name"})
)
public class AgentToolLimitConfig {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agent_client_id", nullable = false, length = 100)
    private String agentClientId;

    @Column(name = "tool_name", nullable = false, length = 120)
    private String toolName;

    @Column(name = "requests_per_minute", nullable = false)
    private int requestsPerMinute;

    @Column(name = "daily_quota", nullable = false)
    private int dailyQuota;

    @Column(name = "write_blast_radius_per_hour", nullable = false)
    private int writeBlastRadiusPerHour;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentToolLimitConfig() {}

    public AgentToolLimitConfig(
            UUID id,
            String agentClientId,
            String toolName,
            int requestsPerMinute,
            int dailyQuota,
            int writeBlastRadiusPerHour
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.agentClientId = agentClientId;
        this.toolName = toolName;
        this.requestsPerMinute = requestsPerMinute;
        this.dailyQuota = dailyQuota;
        this.writeBlastRadiusPerHour = writeBlastRadiusPerHour;
        this.updatedAt = Instant.now();
    }

    public void updateLimits(int requestsPerMinute, int dailyQuota, int writeBlastRadiusPerHour) {
        this.requestsPerMinute = requestsPerMinute;
        this.dailyQuota = dailyQuota;
        this.writeBlastRadiusPerHour = writeBlastRadiusPerHour;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAgentClientId() { return agentClientId; }
    public String getToolName() { return toolName; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public int getDailyQuota() { return dailyQuota; }
    public int getWriteBlastRadiusPerHour() { return writeBlastRadiusPerHour; }
    public Instant getUpdatedAt() { return updatedAt; }
}
