package com.cse444.agentgate.gateway.web;

import com.cse444.agentgate.policy.service.PolicyEngineService;
import com.cse444.agentgate.registry.dto.ToolResponse;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single Unified Model Context Protocol (MCP) Discovery Endpoint for Agents (FR1, FR10).
 * <p>
 * {@code GET /mcp/tools} lists the tools the <em>calling agent</em> is permitted to call: enabled and not
 * killed (FR8), with a healthy downstream (FR10), and allowed for this agent by at least one active ALLOW
 * policy rule (FR1, FR3). Administrators see every available tool. Each call is still evaluated in full by
 * the gateway pipeline, so the list is a view of permissions, not the enforcement point.
 * <p>
 * The live MCP protocol endpoint is kept in step with the registry by
 * {@link com.cse444.agentgate.gateway.service.McpToolListSynchronizer}; the MCP SDK's {@code tools/list} is
 * server-wide, so there per-agent permission is enforced when the tool is called.
 * Lives in the gateway module because it combines the registry with the policy engine.
 * <p>
 * Paginated like every other list endpoint (C3): {@code ?page=&size=}, tools sorted by name; the page holds the
 * tools under {@code tools} (the MCP name) plus {@code number}, {@code size}, {@code totalElements} and
 * {@code totalPages}.
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP Single Front Door", description = "Unified MCP tool discovery endpoint for AI Agents (FR1, FR10)")
public class McpUnifiedEndpointController {

    private final ToolRegistryService toolRegistryService;
    private final PolicyEngineService policyEngineService;
    private final ObjectMapper objectMapper;

    public McpUnifiedEndpointController(
            ToolRegistryService toolRegistryService,
            PolicyEngineService policyEngineService,
            ObjectMapper objectMapper
    ) {
        this.toolRegistryService = toolRegistryService;
        this.policyEngineService = policyEngineService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/tools")
    @Operation(summary = "Discover the healthy tools the calling agent is permitted to call, one page at a time (FR1, FR10, C3)")
    public Map<String, Object> listPermittedHealthyMcpTools(
            Authentication authentication,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        String agentClientId = agentClientIdOf(authentication);
        List<ToolResponse> permitted = toolRegistryService.listAvailableToolsForAgents().stream()
                .filter(tool -> agentClientId == null
                        || policyEngineService.isToolPermittedForAgent(agentClientId, tool.name()))
                .sorted(Comparator.comparing(ToolResponse::name))
                .toList();
        int size = pageable.getPageSize();
        int from = (int) Math.min(pageable.getOffset(), permitted.size());
        int to = Math.min(from + size, permitted.size());

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("tools", permitted.subList(from, to).stream().map(this::toMcpToolDescriptor).toList());
        page.put("number", pageable.getPageNumber());
        page.put("size", size);
        page.put("totalElements", permitted.size());
        page.put("totalPages", (permitted.size() + size - 1) / size);
        return page;
    }

    /**
     * The client id of an agent's OAuth2 JWT, or {@code null} for an administrator: the security filter only
     * lets enabled agents and ADMIN users reach {@code /mcp/**} (AgentGateAuthorizationManager).
     */
    private static String agentClientIdOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            String clientId = jwtAuth.getToken().getClaimAsString("client_id");
            return clientId != null ? clientId : jwtAuth.getToken().getSubject();
        }
        return null;
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
