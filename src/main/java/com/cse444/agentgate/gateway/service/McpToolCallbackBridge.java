package com.cse444.agentgate.gateway.service;

import com.cse444.agentgate.gateway.dto.GatewayToolCallRequest;
import com.cse444.agentgate.gateway.dto.GatewayToolCallResponse;
import com.cse444.agentgate.registry.dto.ToolResponse;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;

/**
 * Bridges Spring AI MCP Server tool callbacks directly into GatewayOrchestrationService
 * so MCP protocol calls undergo full identity, guard, policy, approval, redaction,
 * and audit governance (FR1-FR10).
 */
@Configuration
public class McpToolCallbackBridge {

    private final ToolRegistryService toolRegistryService;
    private final GatewayOrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;

    public McpToolCallbackBridge(
            ToolRegistryService toolRegistryService,
            GatewayOrchestrationService orchestrationService,
            ObjectMapper objectMapper
    ) {
        this.toolRegistryService = toolRegistryService;
        this.orchestrationService = orchestrationService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public ToolCallbackProvider governedMcpToolCallbackProvider() {
        return () -> toolRegistryService.listAvailableToolsForAgents().stream()
                .map(this::createGovernedToolCallback)
                .toArray(ToolCallback[]::new);
    }

    private ToolCallback createGovernedToolCallback(ToolResponse tool) {
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
                            new GatewayToolCallRequest(tool.name(), args, delegatedUser, null)
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
