package com.cse444.agentgate.shared.event;

import java.time.Instant;

/**
 * Domain event published when an agent breaches a rate limit, daily quota,
 * or hourly write blast-radius cap (FR6).
 * Design Pattern: Observer / Event-Driven Architecture.
 */
public record LimitBreachAlertEvent(
        String agentClientId,
        String delegatedUserId,
        String toolName,
        BreachType breachType,
        int configuredLimit,
        String details,
        Instant occurredAt
) {
    public enum BreachType {
        PER_MINUTE_RATE_LIMIT,
        DAILY_QUOTA,
        WRITE_BLAST_RADIUS_CAP
    }
}
