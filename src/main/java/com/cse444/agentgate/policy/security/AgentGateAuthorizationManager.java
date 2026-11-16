package com.cse444.agentgate.policy.security;

import com.cse444.agentgate.identity.service.AgentIdentityService;
import com.cse444.agentgate.policy.dto.PolicyEvaluationDecision;
import com.cse444.agentgate.policy.service.PolicyEngineService;
import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Custom Spring Security AuthorizationManager (FR2, FR3, FR8).
 * Enforces:
 * 1. Valid OAuth2 Client Credentials JWT authentication (FR2)
 * 2. Instant Agent Kill-Switch verification (FR8)
 * 3. Pre-invocation Policy verification when a specific tool path variable is present (FR3)
 */
@Component
public class AgentGateAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final AgentIdentityService agentIdentityService;
    private final ToolRegistryService toolRegistryService;
    private final PolicyEngineService policyEngineService;

    public AgentGateAuthorizationManager(
            AgentIdentityService agentIdentityService,
            ToolRegistryService toolRegistryService,
            PolicyEngineService policyEngineService
    ) {
        this.agentIdentityService = agentIdentityService;
        this.toolRegistryService = toolRegistryService;
        this.policyEngineService = policyEngineService;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authenticationSupplier,
            RequestAuthorizationContext context
    ) {
        Authentication auth = authenticationSupplier.get();
        if (auth == null || !auth.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        // Allow ADMIN role for administrative endpoints
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "SCOPE_admin".equals(a.getAuthority()));
        if (isAdmin) {
            return new AuthorizationDecision(true);
        }

        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return new AuthorizationDecision(false);
        }

        Jwt jwt = jwtAuth.getToken();
        String clientId = jwt.getClaimAsString("client_id");
        if (clientId == null || clientId.isBlank()) {
            clientId = jwt.getSubject();
        }

        // FR8: Verify the agent has not been disabled by Kill Switch
        if (clientId == null || !agentIdentityService.isAgentEnabled(clientId)) {
            return new AuthorizationDecision(false);
        }

        // If the request targets a specific tool without body args (e.g., GET check), verify policy
        String toolName = context.getVariables().get("toolName");
        if (toolName != null && "GET".equalsIgnoreCase(context.getRequest().getMethod())) {
            String delegatedUser = context.getRequest().getHeader("X-On-Behalf-Of-User");
            if (delegatedUser == null || delegatedUser.isBlank()) {
                delegatedUser = jwt.getClaimAsString("delegated_user");
            }
            RegisteredTool tool = toolRegistryService.getRequiredToolByName(toolName);
            PolicyEvaluationDecision decision = policyEngineService.evaluateLiveToolCall(
                    clientId,
                    delegatedUser,
                    tool,
                    Map.of()
            );
            return new AuthorizationDecision(decision.allowed());
        }

        return new AuthorizationDecision(true);
    }
}
