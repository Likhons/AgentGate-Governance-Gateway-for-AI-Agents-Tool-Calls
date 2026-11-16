package com.cse444.agentgate.identity.domain;

import com.cse444.agentgate.shared.domain.AgentStatus;
import com.cse444.agentgate.shared.domain.ClearanceLevel;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Registered OAuth2 Agent Client entity (FR2, FR5, FR8).
 */
@Entity
@Table(name = "agent_clients")
public class AgentClient {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false, length = 255)
    private String clientSecretHash;

    @Column(name = "agent_name", nullable = false, length = 150)
    private String agentName;

    @Column(name = "owning_team", nullable = false, length = 100)
    private String owningTeam;

    @Enumerated(EnumType.STRING)
    @Column(name = "clearance_level", nullable = false, length = 30)
    private ClearanceLevel clearanceLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AgentStatus status;

    @Column(name = "scopes", nullable = false, length = 255)
    private String scopes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentClient() {}

    public AgentClient(
            UUID id,
            String clientId,
            String clientSecretHash,
            String agentName,
            String owningTeam,
            ClearanceLevel clearanceLevel,
            String scopes
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.agentName = agentName;
        this.owningTeam = owningTeam;
        this.clearanceLevel = clearanceLevel;
        this.status = AgentStatus.ENABLED;
        this.scopes = scopes != null ? scopes : "mcp:invoke";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void disable() {
        this.status = AgentStatus.DISABLED;
        this.updatedAt = Instant.now();
    }

    public void enable() {
        this.status = AgentStatus.ENABLED;
        this.updatedAt = Instant.now();
    }

    public boolean isEnabled() {
        return this.status == AgentStatus.ENABLED;
    }

    public UUID getId() { return id; }
    public String getClientId() { return clientId; }
    public String getClientSecretHash() { return clientSecretHash; }
    public String getAgentName() { return agentName; }
    public String getOwningTeam() { return owningTeam; }
    public ClearanceLevel getClearanceLevel() { return clearanceLevel; }
    public AgentStatus getStatus() { return status; }
    public String getScopes() { return scopes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
