package com.cse444.agentgate.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Typed, validated configuration properties for AgentGate (Requirement C2).
 */
@Validated
@ConfigurationProperties(prefix = "agentgate")
public record AgentGateProperties(
        @Valid @NotNull SecurityProperties security,
        @Valid @NotNull PolicyProperties policy,
        @Valid @NotNull ApprovalProperties approval,
        @Valid @NotNull GuardProperties guards,
        @Valid @NotNull LimitProperties limits,
        @Valid @NotNull AuditProperties audit,
        @Valid @NotNull McpProperties mcp
) {

    public record SecurityProperties(
            @NotBlank String jwtSecret,
            @NotNull Duration accessTokenTtl,
            @NotBlank String adminUsername,
            @NotBlank String adminPassword,
            @NotBlank String approverUsername,
            @NotBlank String approverPassword,
            @NotBlank String auditorUsername,
            @NotBlank String auditorPassword
    ) {}

    public record PolicyProperties(
            @NotNull Duration refreshInterval,
            @NotNull Duration lowRiskFailsafeTtl,
            boolean defaultDecisionDeny
    ) {}

    /**
     * @param defaultWebhookUrl approver webhook used when a high-risk tool has no
     *                          tool-specific {@code approvalWebhookUrl} (FR4). Blank disables it.
     */
    public record ApprovalProperties(
            @NotNull Duration defaultTimeout,
            @NotNull Duration webhookTimeout,
            @Pattern(regexp = "^(https?://\\S+)?$", message = "must be blank or an http(s) URL")
            String defaultWebhookUrl
    ) {}

    public record GuardProperties(
            @Min(256) int maxResponseBytes,
            @NotBlank String truncationNotice
    ) {}

    public record LimitProperties(
            @Min(1) int defaultRequestsPerMinute,
            @Min(1) int defaultDailyQuota,
            @Min(1) int defaultWriteBlastRadiusPerHour
    ) {}

    public record AuditProperties(
            @NotBlank String hmacSecret
    ) {}

    /**
     * @param toolSyncInterval how often the live MCP server tool list is reconciled with the
     *                         registry (new, killed and circuit-open tools) (FR1, FR10)
     */
    public record McpProperties(
            @NotNull Duration toolSyncInterval
    ) {}
}
