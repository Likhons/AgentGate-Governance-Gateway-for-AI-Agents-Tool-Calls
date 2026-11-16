package com.cse444.agentgate.registry.dto;

import com.cse444.agentgate.shared.domain.RiskLevel;
import com.cse444.agentgate.shared.domain.ToolType;
import jakarta.validation.constraints.*;

/**
 * Validated request DTO for registering a downstream MCP server tool or plain REST endpoint (FR1, C3).
 */
public record RegisterToolRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_\\-.]{2,120}$", message = "Tool name must be 2-120 alphanumeric/underscore/dash characters")
        String name,

        @NotBlank
        String description,

        @NotBlank
        String inputSchema,

        @NotNull
        RiskLevel riskLevel,

        @NotBlank
        String owningTeam,

        @NotNull
        ToolType toolType,

        boolean writeOperation,

        @NotBlank
        String downstreamUrl,

        @NotBlank
        String httpMethod,

        @Min(100) @Max(60000)
        int timeoutMs
) {}
