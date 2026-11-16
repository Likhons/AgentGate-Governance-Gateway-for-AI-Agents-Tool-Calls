package com.cse444.agentgate.shared.domain;

/**
 * Immutable identity context for an agent tool call (FR2).
 * Carries both the authenticated OAuth2 agent client ID and the optional end user
 * on whose behalf the agent is acting, plus the agent's PII clearance level (FR5).
 */
public record AgentCallIdentity(
        String agentClientId,
        String delegatedUserId,
        ClearanceLevel clearanceLevel
) {}
