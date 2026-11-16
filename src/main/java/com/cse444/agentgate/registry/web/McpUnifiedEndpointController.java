package com.cse444.agentgate.registry.web;

import com.cse444.agentgate.registry.dto.ToolResponse;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single Unified Model Context Protocol (MCP) Discovery Endpoint for Agents (FR1, FR10).
 * Exposes all enabled, non-killed, and healthy tools via GET /mcp/tools (hiding downed tools).
 * Actual MCP tool execution callbacks are bridged in gateway.service.McpToolCallbackBridge
 * so every MCP call traverses the full governance pipeline.
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP Single Front Door", description = "Unified MCP tool discovery endpoint for AI Agents (FR1, FR10)")
public class McpUnifiedEndpointController {

    private final ToolRegistryService toolRegistryService;
    private final ObjectMapper objectMapper;

    public McpUnifiedEndpointController(ToolRegistryService toolRegistryService, ObjectMapper objectMapper) {
        this.toolRegistryService = toolRegistryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the list of healthy, enabled tools visible to agents through one MCP endpoint (FR1, FR10).
     * Any tool that is disabled via Kill Switch (FR8) or whose downstream circuit breaker is OPEN (FR10)
     * is automatically omitted from this list until it recovers.
     */
    @GetMapping("/tools")
    @Operation(summary = "Discover all currently enabled and healthy tools via one MCP endpoint (FR1, FR10)")
    public Map<String, Object> listPermittedHealthyMcpTools() {
        List<Map<String, Object>> mcpTools = toolRegistryService.listAvailableToolsForAgents().stream()
                .map(this::toMcpToolDescriptor)
                .toList();
        return Map.of("tools", mcpTools);
    }

    private Map<String, Object> toMcpToolDescriptor(ToolResponse tool) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", tool.name());
        descriptor.put("description", tool.description());
        descriptor.put("riskLevel", tool.riskLevel().name());
        descriptor.put("owningTeam", tool.owningTeam());
        descriptor.put("writeOperation", tool.writeOperation());
        try {
            descriptor.put("inputSchema", objectMapper.readTree(tool.inputSchema()));
        } catch (Exception ignored) {
            descriptor.put("inputSchema", tool.inputSchema());
        }
        return descriptor;
    }
}
