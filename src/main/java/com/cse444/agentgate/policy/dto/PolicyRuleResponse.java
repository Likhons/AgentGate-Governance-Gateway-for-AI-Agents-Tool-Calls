package com.cse444.agentgate.policy.dto;

import com.cse444.agentgate.policy.domain.PolicyCondition;
import com.cse444.agentgate.policy.domain.PolicyRule;
import com.cse444.agentgate.shared.domain.PolicyEffect;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a versioned PolicyRule (FR3).
 */
public record PolicyRuleResponse(
        UUID id,
        String ruleName,
        int version,
        int priority,
        String agentPattern,
        String toolPattern,
        PolicyEffect effect,
        List<PolicyCondition> conditions,
        boolean dryRun,
        boolean active,
        String description,
        String createdBy,
        Instant createdAt
) {
    public static PolicyRuleResponse from(PolicyRule entity, List<PolicyCondition> parsedConditions) {
        return new PolicyRuleResponse(
                entity.getId(),
                entity.getRuleName(),
                entity.getVersion(),
                entity.getPriority(),
                entity.getAgentPattern(),
                entity.getToolPattern(),
                entity.getEffect(),
                parsedConditions,
                entity.isDryRun(),
                entity.isActive(),
                entity.getDescription(),
                entity.getCreatedBy(),
                entity.getCreatedAt()
        );
    }
}
