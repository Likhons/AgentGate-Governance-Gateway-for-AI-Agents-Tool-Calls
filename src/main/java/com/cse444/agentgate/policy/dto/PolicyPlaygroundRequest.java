package com.cse444.agentgate.policy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for the Policy Playground (FR9) to test a sample tool call
 * against either the currently active policies or an inline list of draft rules.
 */
public record PolicyPlaygroundRequest(
        @NotBlank
        String agentClientId,

        String delegatedUserId,

        @NotBlank
        String toolName,

        Map<String, Object> arguments,

        List<@NotNull @Valid CreatePolicyRuleRequest> draftRules
) {}
