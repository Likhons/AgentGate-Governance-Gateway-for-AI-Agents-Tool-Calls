package com.cse444.agentgate.shared.error;

/**
 * Thrown when an input guard rejects a tool call due to JSON Schema violation
 * or malicious argument smuggling (FR5, QA1).
 */
public class GuardRejectedException extends RuntimeException {

    private final String guardName;

    public GuardRejectedException(String guardName, String message) {
        super(message);
        this.guardName = guardName;
    }

    public String getGuardName() {
        return guardName;
    }
}
