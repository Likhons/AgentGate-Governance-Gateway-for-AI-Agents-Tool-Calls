package com.cse444.agentgate;

import com.cse444.agentgate.approval.service.ApprovalWorkflowService;
import com.cse444.agentgate.approval.web.ApprovalWebPageController;
import com.cse444.agentgate.gateway.dto.AgentApprovalResultResponse;
import com.cse444.agentgate.gateway.dto.GatewayToolCallRequest;
import com.cse444.agentgate.gateway.dto.GatewayToolCallResponse;
import com.cse444.agentgate.gateway.service.GatewayOrchestrationService;
import com.cse444.agentgate.gateway.web.DashboardController;
import com.cse444.agentgate.gateway.web.GatewayController;
import com.cse444.agentgate.identity.service.AgentIdentityService;
import com.cse444.agentgate.identity.web.AgentIdentityController;
import com.cse444.agentgate.policy.security.AgentGateAuthorizationManager;
import com.cse444.agentgate.policy.security.SecurityConfig;
import com.cse444.agentgate.policy.service.PolicyEngineService;
import com.cse444.agentgate.policy.web.PolicyController;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import com.cse444.agentgate.shared.config.AgentGateJacksonConfig;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.domain.ApprovalStateType;
import com.cse444.agentgate.shared.error.GlobalProblemDetailsHandler;
import com.cse444.agentgate.shared.error.GuardRejectedException;
import com.cse444.agentgate.shared.error.LimitExceededException;
import com.cse444.agentgate.shared.event.LimitBreachAlertEvent.BreachType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMVC Slice Test verifying Spring Security RBAC, AgentGateAuthorizationManager,
 * OAuth2 JWT checks, Bean Validation, and RFC 9457 Problem Details (C3, FR2, FR3, FR8).
 */
@WebMvcTest(controllers = {
        DashboardController.class,
        ApprovalWebPageController.class,
        GatewayController.class,
        PolicyController.class,
        AgentIdentityController.class
})
@Import({
        SecurityConfig.class,
        AgentGateAuthorizationManager.class,
        AgentGateJacksonConfig.class,
        GlobalProblemDetailsHandler.class
})
@EnableConfigurationProperties(AgentGateProperties.class)
@ActiveProfiles("test")
class WebSecurityAndProblemDetailsSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private GatewayOrchestrationService gatewayOrchestrationService;

    @MockitoBean
    private PolicyEngineService policyEngineService;

    @MockitoBean
    private AgentIdentityService agentIdentityService;

    @MockitoBean
    private ToolRegistryService toolRegistryService;

    @MockitoBean
    private ApprovalWorkflowService approvalWorkflowService;

    @Test
    @DisplayName("C3 Security & ProblemDetail: Unauthenticated gateway call returns 401 RFC 9457 ProblemDetail")
    void unauthenticatedGatewayCallReturns401ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/gateway/call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"lookup_order\",\"arguments\":{\"customerId\":\"cust-1\"}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication Required"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("FR2/FR3 RBAC: Agent JWT can call gateway when enabled, but is forbidden from Admin policy APIs")
    void agentJwtAuthorizedForGatewayButForbiddenFromAdminApis() throws Exception {
        String agentJwt = issueTestJwt("support-bot", "cust-100");
        when(agentIdentityService.isAgentEnabled("support-bot")).thenReturn(true);
        when(gatewayOrchestrationService.processToolCall(eq("support-bot"), any(GatewayToolCallRequest.class)))
                .thenReturn(new GatewayToolCallResponse(
                        UUID.randomUUID(), "trace-123", "lookup_order", "EXECUTED",
                        "allow-own-customer-order-read", 1, null, null, "{\"ok\":true}", 2L, Instant.now()
                ));

        mockMvc.perform(post("/api/v1/gateway/call")
                        .header("Authorization", "Bearer " + agentJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"lookup_order\",\"arguments\":{\"customerId\":\"cust-100\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("EXECUTED"));

        mockMvc.perform(get("/api/v1/policies")
                        .header("Authorization", "Bearer " + agentJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access Denied"));
    }

    @Test
    @DisplayName("FR8 AuthorizationManager: Disabled agent JWT is immediately rejected at the security filter")
    void disabledAgentJwtIsBlockedByAuthorizationManager() throws Exception {
        String killedAgentJwt = issueTestJwt("rogue-bot", "cust-1");
        when(agentIdentityService.isAgentEnabled("rogue-bot")).thenReturn(false);

        mockMvc.perform(post("/api/v1/gateway/call")
                        .header("Authorization", "Bearer " + killedAgentJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"lookup_order\",\"arguments\":{\"customerId\":\"cust-1\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access Denied"));
    }

    @Test
    @DisplayName("C3 Validation & RFC 9457: Bean Validation, Guard rejection, and Rate Limit return ProblemDetail JSON")
    void validationGuardAndRateLimitReturnRfc9457ProblemDetails() throws Exception {
        String agentJwt = issueTestJwt("support-bot", "cust-100");
        when(agentIdentityService.isAgentEnabled("support-bot")).thenReturn(true);

        // 1. Blank toolName -> 400 Bean Validation ProblemDetail
        mockMvc.perform(post("/api/v1/gateway/call")
                        .header("Authorization", "Bearer " + agentJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bean Validation Failed"));

        // 2. Guard rejection -> 422 ProblemDetail
        when(gatewayOrchestrationService.processToolCall(eq("support-bot"), any(GatewayToolCallRequest.class)))
                .thenThrow(new GuardRejectedException("ArgumentSmugglingSecurityGuard", "Blocked injection"));
        mockMvc.perform(post("/api/v1/gateway/call")
                        .header("Authorization", "Bearer " + agentJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"lookup_order\",\"arguments\":{\"customerId\":\"bad\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.guard").value("ArgumentSmugglingSecurityGuard"));

        // 3. Rate limit breach -> 429 ProblemDetail
        when(gatewayOrchestrationService.processToolCall(eq("support-bot"), any(GatewayToolCallRequest.class)))
                .thenThrow(new LimitExceededException(BreachType.WRITE_BLAST_RADIUS_CAP, 20, "Blast cap exceeded"));
        mockMvc.perform(post("/api/v1/gateway/call")
                        .header("Authorization", "Bearer " + agentJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"issue_refund\",\"arguments\":{\"orderId\":\"O-1\",\"amount\":10}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.breachType").value("WRITE_BLAST_RADIUS_CAP"));

        // 4. Admin Basic Auth can hit /api/v1/policies/reload
        mockMvc.perform(post("/api/v1/policies/reload")
                        .with(httpBasic("user", "test-admin-pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELOADED"));
    }

    @Test
    @DisplayName("FR4 Agent Result Endpoint: an enabled agent JWT can poll its approval result; anonymous callers get 401")
    void agentJwtCanPollApprovalResultButAnonymousCannot() throws Exception {
        UUID approvalId = UUID.randomUUID();
        String agentJwt = issueTestJwt("support-bot", "cust-100");
        when(agentIdentityService.isAgentEnabled("support-bot")).thenReturn(true);
        when(gatewayOrchestrationService.getApprovalResultForAgent("support-bot", approvalId))
                .thenReturn(new AgentApprovalResultResponse(
                        approvalId, "bulk_payout", ApprovalStateType.REJECTED, "REJECTED", true,
                        "Rejected by a human approver; the tool was not called.", "Unknown vendor",
                        null, Instant.now(), Instant.now()
                ));

        mockMvc.perform(get("/api/v1/gateway/approvals/{approvalId}", approvalId)
                        .header("Authorization", "Bearer " + agentJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultStatus").value("REJECTED"))
                .andExpect(jsonPath("$.terminal").value(true));

        mockMvc.perform(get("/api/v1/gateway/approvals/{approvalId}", approvalId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Dashboard root renders publicly while HTML console challenges browsers with Basic realm")
    void dashboardRootIsPublicAndBrowserConsolePromptsForBasicAuth() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/approvals/console")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"AgentGate\""))
                .andExpect(jsonPath("$.status").value(401));
    }

    private String issueTestJwt(String clientId, String delegatedUser) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://agentgate.cse444.com")
                .subject(clientId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("client_id", clientId)
                .claim("delegated_user", delegatedUser)
                .claim("scope", "mcp:invoke")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
