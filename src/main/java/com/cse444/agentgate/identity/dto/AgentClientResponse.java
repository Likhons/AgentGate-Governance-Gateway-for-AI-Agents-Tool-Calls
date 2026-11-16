package com.cse444.agentgate.identity.dto;

import com.cse444.agentgate.identity.domain.AgentClient;
import com.cse444.agentgate.shared.domain.AgentStatus;
import com.cse444.agentgate.shared.domain.ClearanceLevel;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a registered OAuth2 Agent Client (FR2, FR8).
 */
public record AgentClientResponse(
        UUID id,
        String clientId,
        String agentName,
        String owningTeam,
        ClearanceLevel clearanceLevel,
        AgentStatus status,
        String scopes,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentClientResponse from(AgentClient entity) {
        return new AgentClientResponse(
                entity.getId(),
                entity.getClientId(),
                entity.getAgentName(),
                entity.getOwningTeam(),
                entity.getClearanceLevel(),
                entity.getStatus(),
                entity.getScopes(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
