package com.cse444.agentgate.guard.dto;

import com.cse444.agentgate.guard.domain.LimitBreachAlert;
import com.cse444.agentgate.shared.event.LimitBreachAlertEvent.BreachType;

import java.time.Instant;
import java.util.UUID;

/**
 * A raised limit-breach alert as listed for administrators (FR6, C3).
 */
public record LimitBreachAlertResponse(
        UUID id,
        String agentClientId,
        String delegatedUserId,
        String toolName,
        BreachType breachType,
        int configuredLimit,
        String message,
        boolean webhookNotified,
        Instant raisedAt
) {
    public static LimitBreachAlertResponse from(LimitBreachAlert alert) {
        return new LimitBreachAlertResponse(
                alert.getId(),
                alert.getAgentClientId(),
                alert.getDelegatedUserId(),
                alert.getToolName(),
                alert.getBreachType(),
                alert.getConfiguredLimit(),
                alert.getMessage(),
                alert.getWebhookUrl() != null,
                alert.getRaisedAt()
        );
    }
}
