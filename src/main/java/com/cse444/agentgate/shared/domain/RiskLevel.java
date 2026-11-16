package com.cse444.agentgate.shared.domain;

/**
 * Tool risk classification (FR1, FR4, QA4).
 * - LOW: Executes immediately if allowed by policy; uses 5-minute cached policy during DB outage (QA4).
 * - MEDIUM: Executes immediately if allowed by policy; fails closed during policy store outage (QA4).
 * - HIGH: Pauses for human-in-the-loop approval before execution (FR4) and fails closed during outage (QA4).
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
