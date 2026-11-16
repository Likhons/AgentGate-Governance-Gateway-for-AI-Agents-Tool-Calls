package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.dto.GuardInspectionContext;

/**
 * Design Pattern: Chain of Responsibility & Strategy Pattern (FR5, FR6).
 * Ordered, pluggable Spring bean interface for pre-execution input guards.
 * Adding a new input guard requires only declaring a new @Component @Order(...) bean
 * without modifying existing pipeline classes (Open/Closed Principle).
 */
public interface InputCallGuard {

    String guardName();

    void verifyInput(GuardInspectionContext context);
}
