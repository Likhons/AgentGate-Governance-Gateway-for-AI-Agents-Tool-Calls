package com.cse444.agentgate.registry.adapter;

import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.shared.domain.ToolType;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Design Pattern: Adapter Pattern (FR1).
 * Adapts a downstream Model Context Protocol (MCP) Server endpoint using the MCP JSON-RPC 2.0
 * tools/call specification so downstream MCP tools and plain REST tools share a uniform contract.
 */
@Component
public class McpDownstreamToolAdapter implements DownstreamToolAdapter {

    private final RestClient restClient;

    public McpDownstreamToolAdapter(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ToolType supportsType() {
        return ToolType.MCP;
    }

    @Override
    public DownstreamExecutionResult invoke(RegisteredTool tool, Map<String, Object> arguments) {
        long startNanos = System.nanoTime();

        Map<String, Object> mcpJsonRpcRequest = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/call",
                "params", Map.of(
                        "name", tool.getName(),
                        "arguments", arguments != null ? arguments : Map.of()
                )
        );

        ResponseEntity<String> response = restClient.post()
                .uri(tool.getDownstreamUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(mcpJsonRpcRequest)
                .retrieve()
                .toEntity(String.class);

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        return new DownstreamExecutionResult(
                response.getStatusCode().is2xxSuccessful(),
                response.getStatusCode().value(),
                response.getBody() != null ? response.getBody() : "{}",
                latencyMs
        );
    }
}
