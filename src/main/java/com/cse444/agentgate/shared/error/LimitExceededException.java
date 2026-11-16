package com.cse444.agentgate.shared.error;

import com.cse444.agentgate.shared.event.LimitBreachAlertEvent.BreachType;

/**
 * Thrown when an agent exceeds its per-minute rate limit, daily quota,
 * or write-call blast-radius cap (FR6).
 */
public class LimitExceededException extends RuntimeException {

    private final BreachType breachType;
    private final int configuredLimit;

    public LimitExceededException(BreachType breachType, int configuredLimit, String message) {
        super(message);
        this.breachType = breachType;
        this.configuredLimit = configuredLimit;
    }

    public BreachType getBreachType() {
        return breachType;
    }

    public int getConfiguredLimit() {
        return configuredLimit;
    }
}
