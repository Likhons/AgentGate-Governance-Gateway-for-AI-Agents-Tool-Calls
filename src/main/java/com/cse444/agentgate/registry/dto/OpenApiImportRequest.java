package com.cse444.agentgate.registry.dto;

import com.cse444.agentgate.shared.domain.RiskLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload to import all REST endpoints from an OpenAPI 3.x specification (FR1, QA5).
 */
public record OpenApiImportRequest(
        @NotBlank
        String openApiContent,

        @NotBlank
        String owningTeam,

        @NotNull
        RiskLevel defaultRiskLevel,

        String baseUrlOverride,

        @Min(100) @Max(60000)
        int defaultTimeoutMs
) {}
