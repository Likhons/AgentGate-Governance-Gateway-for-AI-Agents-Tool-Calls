package com.cse444.agentgate.policy.dto;

import com.cse444.agentgate.shared.domain.PolicyEffect;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of evaluating a tool call against active or draft policies (FR3, FR7, FR9, QA4).
 * Carries the exact policy rule name and version that decided the call so it can be recorded
 * in the tamper-evident audit trail (FR7) or displayed in the Policy Playground (FR9).
 */
public record PolicyEvaluationDecision(
        boolean allowed,
        PolicyEffect effectiveDecision,
        UUID matchedRuleId,
        String matchedRuleName,
        int matchedRuleVersion,
        boolean dryRunWouldBlock,
        String dryRunRuleName,
        boolean evaluatedFromFailsafeCache,
        String reason,
        Instant evaluatedAt
) {}
