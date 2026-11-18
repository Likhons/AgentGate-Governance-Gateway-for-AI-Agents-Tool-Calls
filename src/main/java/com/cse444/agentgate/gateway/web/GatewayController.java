package com.cse444.agentgate.gateway.web;

import com.cse444.agentgate.gateway.dto.AgentApprovalResultResponse;
import com.cse444.agentgate.gateway.dto.GatewayToolCallRequest;
import com.cse444.agentgate.gateway.dto.GatewayToolCallResponse;
import com.cse444.agentgate.gateway.service.GatewayOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Single Front-Door Gateway Controller for AI Agent Tool Calls (FR1-FR10).
 */
@RestController
@RequestMapping("/api/v1/gateway")
@Tag(name = "AgentGate Front-Door Gateway (FR1-FR10)", description = "Execute governed MCP/REST tool calls through the full policy, guard, approval, and audit pipeline")
public class GatewayController {

    private final GatewayOrchestrationService orchestrationService;

    public GatewayController(GatewayOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping("/call")
    @Operation(summary = "Execute a governed tool call through AgentGate (FR1-FR10)")
    public GatewayToolCallResponse callTool(
            @Valid @RequestBody GatewayToolCallRequest request,
            @RequestHeader(value = "X-On-Behalf-Of-User", required = false) String headerDelegatedUser,
            Authentication authentication
    ) {
        String clientId = extractClientId(authentication);
        String delegatedUser = request.delegatedUserId() != null && !request.delegatedUserId().isBlank()
                ? request.delegatedUserId()
                : extractDelegatedUser(authentication, headerDelegatedUser);

        GatewayToolCallRequest enriched = new GatewayToolCallRequest(
                request.toolName(),
                request.arguments(),
                delegatedUser
        );
        return orchestrationService.processToolCall(clientId, enriched);
    }

    @GetMapping("/approvals/{approvalId}")
    @Operation(summary = "Poll the current or final result of your own paused high-risk tool call (FR4)")
    public AgentApprovalResultResponse getApprovalResult(
            @PathVariable UUID approvalId,
            Authentication authentication
    ) {
        return orchestrationService.getApprovalResultForAgent(extractClientId(authentication), approvalId);
    }

    private String extractClientId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            String cid = jwtAuth.getToken().getClaimAsString("client_id");
            return cid != null ? cid : jwtAuth.getToken().getSubject();
        }
        return authentication != null ? authentication.getName() : "anonymous-agent";
    }

    private String extractDelegatedUser(Authentication authentication, String headerDelegatedUser) {
        if (headerDelegatedUser != null && !headerDelegatedUser.isBlank()) {
            return headerDelegatedUser;
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getClaimAsString("delegated_user");
        }
        return null;
    }
}
