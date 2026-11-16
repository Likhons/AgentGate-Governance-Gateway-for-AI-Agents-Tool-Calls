package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.dto.GuardInspectionContext;

/**
 * Design Pattern: Chain of Responsibility & Decorator Pattern (FR5).
 * Ordered, pluggable Spring bean interface for post-execution output guards
 * (e.g., clearance-aware PII redaction and payload truncation).
 */
public interface OutputResultGuard {

    String guardName();

    String transformOutput(GuardInspectionContext context, String rawOutput);
}
