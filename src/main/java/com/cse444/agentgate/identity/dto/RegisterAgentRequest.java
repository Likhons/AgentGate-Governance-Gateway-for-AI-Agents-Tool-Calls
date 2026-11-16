package com.cse444.agentgate.identity.dto;

import com.cse444.agentgate.shared.domain.ClearanceLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO to register an AI Agent as an OAuth2 client (FR2, FR5).
 */
public record RegisterAgentRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_\\-.]{3,100}$")
        String clientId,

        @NotBlank
        String clientSecret,

        @NotBlank
        String agentName,

        @NotBlank
        String owningTeam,

        @NotNull
        ClearanceLevel clearanceLevel,

        String scopes
) {}
