package com.cse444.agentgate.guard.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to configure per-agent and per-tool rate limits, daily quotas,
 * and write blast-radius caps (FR6).
 */
public record UpsertLimitConfigRequest(
        @NotBlank
        String agentClientId,

        @NotBlank
        String toolName,

        @Min(1)
        int requestsPerMinute,

        @Min(1)
        int dailyQuota,

        @Min(1)
        int writeBlastRadiusPerHour
) {}
