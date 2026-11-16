package com.cse444.agentgate.guard.dto;

import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.shared.domain.AgentCallIdentity;

import java.util.Map;

/**
 * Context passed through the ordered InputCallGuard and OutputResultGuard chains (FR5, FR6).
 */
public record GuardInspectionContext(
        AgentCallIdentity identity,
        RegisteredTool tool,
        Map<String, Object> arguments
) {}
