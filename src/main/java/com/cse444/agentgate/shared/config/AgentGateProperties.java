package com.cse444.agentgate.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Typed, validated configuration properties for AgentGate (Requirement C2).
 * Secrets have no defaults in the repository: they come from environment variables
 * (Docker Compose / CI) or a git-ignored {@code .env} file, and startup fails fast when
 * a secret is missing or too short to be a safe HMAC key.
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
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (256-bit HMAC-SHA256 key)")
            String jwtSecret,
            @NotNull Duration accessTokenTtl,
            @NotBlank String adminUsername,
            @NotBlank @Size(min = 8) String adminPassword,
            @NotBlank String approverUsername,
            @NotBlank @Size(min = 8) String approverPassword,
            @NotBlank String auditorUsername,
            @NotBlank @Size(min = 8) String auditorPassword
    ) {}

    /**
     * @param storeProbeInterval how often the policy store (PostgreSQL) is probed for an outage (QA4)
     * @param storeProbeTimeout  how long a probe may take before the store counts as unreachable (QA4)
     */
    public record PolicyProperties(
            @NotNull Duration refreshInterval,
            @NotNull Duration lowRiskFailsafeTtl,
            boolean defaultDecisionDeny,
            @NotNull Duration storeProbeInterval,
            @NotNull Duration storeProbeTimeout
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

    /**
     * @param alertCooldown   at most one limit-breach alert per agent, tool and breach type in this window (FR6)
     * @param alertWebhookUrl operations webhook that receives limit-breach alerts; blank disables it (FR6)
     */
    public record LimitProperties(
            @Min(1) int defaultRequestsPerMinute,
            @Min(1) int defaultDailyQuota,
            @Min(1) int defaultWriteBlastRadiusPerHour,
            @NotNull Duration alertCooldown,
            @Pattern(regexp = "^(https?://\\S+)?$", message = "must be blank or an http(s) URL")
            String alertWebhookUrl
    ) {}

    public record AuditProperties(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (256-bit HMAC-SHA256 key)")
            String hmacSecret
    ) {}

    /**
     * @param toolSyncInterval how often the live MCP server tool list is reconciled with the
     *                         registry (new, killed and circuit-open tools) (FR1, FR10)
     */
    public record McpProperties(
            @NotNull Duration toolSyncInterval
    ) {}
}
