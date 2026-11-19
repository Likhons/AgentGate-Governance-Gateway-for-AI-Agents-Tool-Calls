package com.cse444.agentgate.policy.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Value object representing a single argument condition on a PolicyRule (FR3).
 * Examples:
 * - { "field": "amount", "operator": "LTE", "expectedValue": "2000" } -> Refunds up to 2,000 BDT
 * - { "field": "customerId", "operator": "MATCHES_DELEGATED_USER", "expectedValue": "$user" } -> Reads limited to requesting customer's own records
 * A condition without a field, operator or expected value cannot be evaluated, so the API rejects it (C3, FR3).
 */
public record PolicyCondition(
        @NotBlank String field,
        @NotNull Operator operator,
        @NotNull String expectedValue
) {
    public enum Operator {
        EQ,
        NEQ,
        LTE,
        GTE,
        LT,
        GT,
        IN,
        MATCHES_DELEGATED_USER,
        REGEX
    }
}
