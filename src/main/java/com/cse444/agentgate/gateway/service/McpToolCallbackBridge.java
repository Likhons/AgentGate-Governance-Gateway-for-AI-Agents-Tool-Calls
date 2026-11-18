package com.cse444.agentgate.gateway.service;

import com.cse444.agentgate.gateway.dto.GatewayToolCallRequest;
import com.cse444.agentgate.gateway.dto.GatewayToolCallResponse;
import com.cse444.agentgate.registry.dto.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Design Pattern: Adapter (FR1).
 * Adapts a registered tool into a Spring AI {@link ToolCallback} whose execution is routed through
 * {@link GatewayOrchestrationService}, so MCP protocol calls undergo the full identity, guard,
 * policy, approval, redaction and audit governance (FR1-FR10).
 * The callbacks are published to (and withdrawn from) the live MCP server at runtime by
 * {@link McpToolListSynchronizer}, instead of being frozen at startup.
 */
@Component
public class McpToolCallbackBridge {

    private final GatewayOrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;

    public McpToolCallbackBridge(
            GatewayOrchestrationService orchestrationService,
            ObjectMapper objectMapper
    ) {
        this.orchestrationService = orchestrationService;
        this.objectMapper = objectMapper;
    }

    public ToolCallback createGovernedToolCallback(ToolResponse tool) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                try {
                    Map<String, Object> args = (toolInput == null || toolInput.isBlank())
                            ? Map.of()
                            : objectMapper.readValue(toolInput, new TypeReference<>() {});
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    String clientId = extractClientId(auth);
                    String delegatedUser = extractDelegatedUser(auth);

                    GatewayToolCallResponse response = orchestrationService.processToolCall(
                            clientId,
                            new GatewayToolCallRequest(tool.name(), args, delegatedUser)
                    );
                    return response.redactedResultPayload();
                } catch (Exception e) {
                    throw new IllegalStateException("Governed MCP tool execution failed: " + e.getMessage(), e);
                }
            }
        };
    }

    private String extractClientId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String cid = jwtAuth.getToken().getClaimAsString("client_id");
            return cid != null ? cid : jwtAuth.getToken().getSubject();
        }
        return auth != null ? auth.getName() : "anonymous-agent";
    }

    private String extractDelegatedUser(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getClaimAsString("delegated_user");
        }
        return null;
    }
}
