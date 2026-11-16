package com.cse444.agentgate.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        @Valid @NotNull AuditProperties audit
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

    public record ApprovalProperties(
            @NotNull Duration defaultTimeout,
            @NotNull Duration webhookTimeout
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
}
