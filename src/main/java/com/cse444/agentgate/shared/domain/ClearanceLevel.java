package com.cse444.agentgate.shared.domain;

/**
 * Agent PII clearance level for output redaction guards (FR2, FR5).
 * - NONE: Redacts all emails, Bangladeshi phone numbers, and National IDs (NIDs).
 * - INTERNAL: Redacts Bangladeshi phone numbers and NIDs, preserves business emails.
 * - FULL_PII: Preserves all fields (for high-trust compliance agents only).
 */
public enum ClearanceLevel {
    NONE,
    INTERNAL,
    FULL_PII
}
