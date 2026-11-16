package com.cse444.agentgate.policy.domain;

/**
 * Value object representing a single argument condition on a PolicyRule (FR3).
 * Examples:
 * - { "field": "amount", "operator": "LTE", "expectedValue": "2000" } -> Refunds up to 2,000 BDT
 * - { "field": "customerId", "operator": "MATCHES_DELEGATED_USER", "expectedValue": "$user" } -> Reads limited to requesting customer's own records
 */
public record PolicyCondition(
        String field,
        Operator operator,
        String expectedValue
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
