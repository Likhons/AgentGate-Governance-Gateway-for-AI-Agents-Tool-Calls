package com.cse444.agentgate.registry.dto;

import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.shared.domain.RiskLevel;
import com.cse444.agentgate.shared.domain.ToolStatus;
import com.cse444.agentgate.shared.domain.ToolType;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing a registered tool and its live circuit-breaker health state (FR1, FR8, FR10).
 */
public record ToolResponse(
        UUID id,
        String name,
        String description,
        String inputSchema,
        RiskLevel riskLevel,
        String owningTeam,
        ToolType toolType,
        ToolStatus status,
        boolean writeOperation,
        String downstreamUrl,
        String httpMethod,
        int timeoutMs,
        boolean downstreamHealthy,
        String circuitBreakerState,
        Instant createdAt,
        Instant updatedAt
) {
    public static ToolResponse from(RegisteredTool tool, boolean healthy, String circuitState) {
        return new ToolResponse(
                tool.getId(),
                tool.getName(),
                tool.getDescription(),
                tool.getInputSchema(),
                tool.getRiskLevel(),
                tool.getOwningTeam(),
                tool.getToolType(),
                tool.getStatus(),
                tool.isWriteOperation(),
                tool.getDownstreamUrl(),
                tool.getHttpMethod(),
                tool.getTimeoutMs(),
                healthy,
                circuitState,
                tool.getCreatedAt(),
                tool.getUpdatedAt()
        );
    }
}
