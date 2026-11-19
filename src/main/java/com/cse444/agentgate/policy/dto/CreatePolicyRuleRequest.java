package com.cse444.agentgate.policy.dto;

import com.cse444.agentgate.policy.domain.PolicyCondition;
import com.cse444.agentgate.shared.domain.PolicyEffect;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request DTO to create a new policy rule or a new version of an existing rule (FR3).
 */
public record CreatePolicyRuleRequest(
        @NotBlank
        String ruleName,

        @Min(1)
        int priority,

        @NotBlank
        String agentPattern,

        @NotBlank
        String toolPattern,

        @NotNull
        PolicyEffect effect,

        List<@NotNull @Valid PolicyCondition> conditions,

        boolean dryRun,

        String description
) {}
