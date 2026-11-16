package com.cse444.agentgate.shared.event;

import java.time.Instant;

/**
 * Domain event published when an administrator activates a Kill Switch (FR8).
 * Design Pattern: Observer / Event-Driven Architecture.
 * Consumed by the Approval module to immediately cancel pending approvals for the disabled
 * agent, tool, or all write tools without coupling Registry/Identity to Approval.
 */
public record KillSwitchActivatedEvent(
        Scope scope,
        String targetIdentifier,
        String reason,
        String triggeredBy,
        Instant occurredAt
) {
    public enum Scope {
        AGENT,
        TOOL,
        ALL_WRITE_TOOLS
    }
}
