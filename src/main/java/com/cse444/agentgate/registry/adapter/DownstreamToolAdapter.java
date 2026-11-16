package com.cse444.agentgate.registry.adapter;

import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.shared.domain.ToolType;

import java.util.Map;

/**
 * Design Pattern: Adapter & Strategy Pattern (FR1).
 * Adapts heterogeneous downstream tool protocols (plain REST endpoints vs. MCP servers)
 * into a unified invocation interface for the AgentGate governance pipeline.
 */
public interface DownstreamToolAdapter {

    ToolType supportsType();

    DownstreamExecutionResult invoke(RegisteredTool tool, Map<String, Object> arguments);

    record DownstreamExecutionResult(
            boolean success,
            int statusCode,
            String payload,
            long latencyMs
    ) {}
}
