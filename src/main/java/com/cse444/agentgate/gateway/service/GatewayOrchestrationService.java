package com.cse444.agentgate.gateway.service;

import com.cse444.agentgate.approval.dto.ApprovalRequestResponse;
import com.cse444.agentgate.approval.service.ApprovalWorkflowService;
import com.cse444.agentgate.gateway.dto.AgentApprovalResultResponse;
import com.cse444.agentgate.gateway.dto.GatewayToolCallRequest;
import com.cse444.agentgate.gateway.dto.GatewayToolCallResponse;
import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.guard.service.GuardPipelineService;
import com.cse444.agentgate.identity.service.AgentIdentityService;
import com.cse444.agentgate.policy.dto.PolicyEvaluationDecision;
import com.cse444.agentgate.policy.service.PolicyEngineService;
import com.cse444.agentgate.registry.adapter.DownstreamToolAdapter.DownstreamExecutionResult;
import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import com.cse444.agentgate.shared.domain.AgentCallIdentity;
import com.cse444.agentgate.shared.domain.RiskLevel;
import com.cse444.agentgate.shared.error.GuardRejectedException;
import com.cse444.agentgate.shared.error.LimitExceededException;
import com.cse444.agentgate.shared.event.ToolCallAuditRecordedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Design Pattern: Proxy / Facade / Command Orchestrator (FR1-FR10).
 * Single Front-Door Governance Pipeline between AI Agents and Downstream Tools:
 * 1. Resolves OAuth2 Agent Identity & verifies Agent Kill Switch (FR2, FR8)
 * 2. Looks up RegisteredTool & verifies Tool / Write-Global Kill Switches (FR1, FR8)
 * 3. Evaluates Ordered Input Guards: JSON Schema validation, Anti-Smuggling, and Bucket4j Rate/Blast Limits (FR5, FR6, QA1)
 * 4. Evaluates Hot-Reloaded Policy Engine with Default-Deny & QA4 Fail-Safe rules (FR3, QA4)
 * 5. Pauses HIGH-risk tools in the Approval State Machine (FR4) OR invokes Downstream Tool via Resilience4j (FR10)
 * 6. Runs Ordered Output Guards: Clearance-based PII Redaction & Payload Truncation (FR5)
 * 7. Publishes ToolCallAuditRecordedEvent in a finally block so EVERY call is hash-chained in the Audit Trail (FR7, QA3)
 */
@Service
public class GatewayOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(GatewayOrchestrationService.class);

    /** Agent-facing endpoint (GatewayController) that reports the final result of a held call (FR4). */
    public static final String APPROVAL_STATUS_PATH = "/api/v1/gateway/approvals/";

    private final AgentIdentityService agentIdentityService;
    private final ToolRegistryService toolRegistryService;
    private final PolicyEngineService policyEngineService;
    private final GuardPipelineService guardPipelineService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final ObjectProvider<Tracer> tracerProvider;

    public GatewayOrchestrationService(
            AgentIdentityService agentIdentityService,
            ToolRegistryService toolRegistryService,
            PolicyEngineService policyEngineService,
            GuardPipelineService guardPipelineService,
            ApprovalWorkflowService approvalWorkflowService,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry,
            ObjectProvider<Tracer> tracerProvider
    ) {
        this.agentIdentityService = agentIdentityService;
        this.toolRegistryService = toolRegistryService;
        this.policyEngineService = policyEngineService;
        this.guardPipelineService = guardPipelineService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.tracerProvider = tracerProvider;
    }

    public GatewayToolCallResponse processToolCall(
            String authenticatedClientId,
            GatewayToolCallRequest request
    ) {
        long startNanos = System.nanoTime();
        long downstreamNanos = 0L;
        UUID callId = UUID.randomUUID();
        String traceId = resolveTraceId(callId);
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
        String redactedArgsJson = guardPipelineService.redactArgumentsForAudit(args);

        String policyRuleName = "UNDECIDED";
        int policyRuleVersion = 0;
        UUID approvalId = null;
        String approvalState = null;
        String outcome = "UNKNOWN";

        MDC.put("traceId", traceId);
        MDC.put("callId", callId.toString());
        MDC.put("agentClientId", authenticatedClientId != null ? authenticatedClientId : "anonymous");
        MDC.put("toolName", request.toolName());

        try {
            // Step 1: Verify Agent Identity & Kill Switch (FR2, FR8)
            AgentCallIdentity identity = agentIdentityService.verifyAndResolveAgentIdentity(
                    authenticatedClientId,
                    request.delegatedUserId()
            );

            // Step 2: Lookup Tool & check Tool / Global Write Kill Switch (FR1, FR8)
            RegisteredTool tool = toolRegistryService.getRequiredToolByName(request.toolName());
            if (!tool.isEnabled() || (tool.isWriteOperation() && toolRegistryService.isAllWriteToolsKilled())) {
                outcome = "KILL_SWITCH_BLOCKED";
                policyRuleName = "KILL_SWITCH";
                throw new IllegalStateException("Tool '" + tool.getName() + "' is disabled by Kill Switch (FR8)");
            }

            // Step 3: Run Pluggable Input Guards (Schema validation, Anti-Smuggling, Rate Limits & Blast Radius - FR5, FR6)
            GuardInspectionContext guardContext = new GuardInspectionContext(identity, tool, args);
            guardPipelineService.runInputGuards(guardContext);

            // Step 4: Evaluate Policy Engine (FR3, QA4)
            PolicyEvaluationDecision policyDecision = policyEngineService.evaluateLiveToolCall(
                    identity.agentClientId(),
                    identity.delegatedUserId(),
                    tool,
                    args
            );
            policyRuleName = policyDecision.matchedRuleName();
            policyRuleVersion = policyDecision.matchedRuleVersion();

            if (!policyDecision.allowed()) {
                outcome = "DENIED_BY_POLICY";
                throw new IllegalStateException("Policy denied tool call: " + policyDecision.reason());
            }

            // Step 5: High-Risk Human Approval Pause (FR4)
            if (tool.getRiskLevel() == RiskLevel.HIGH) {
                ApprovalRequestResponse pendingApproval = approvalWorkflowService.createPendingApproval(
                        identity.agentClientId(),
                        identity.delegatedUserId(),
                        tool,
                        args,
                        policyRuleName,
                        policyRuleVersion,
                        null
                );
                approvalId = pendingApproval.id();
                approvalState = pendingApproval.state().name();
                outcome = "HELD_FOR_APPROVAL";
                long gatewayOverheadMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("Held high-risk tool call for human approval: approvalId={}, rule={}@v{}",
                        approvalId, policyRuleName, policyRuleVersion);

                return new GatewayToolCallResponse(
                        callId,
                        traceId,
                        tool.getName(),
                        outcome,
                        policyRuleName,
                        policyRuleVersion,
                        approvalId,
                        approvalState,
                        pendingApprovalPayload(approvalId, pendingApproval.expiresAt()),
                        gatewayOverheadMs,
                        Instant.now()
                );
            }

            // Step 6: Execute Downstream Tool with Resilience4j Circuit Breaker & Timeout (FR10)
            long dsStart = System.nanoTime();
            DownstreamExecutionResult downstreamResult = toolRegistryService.executeDownstreamTool(tool, args);
            downstreamNanos = System.nanoTime() - dsStart;

            // Step 7: Run Pluggable Output Guards (Clearance PII Redaction & Payload Truncation - FR5)
            String sanitizedPayload = guardPipelineService.runOutputGuards(guardContext, downstreamResult.payload());
            outcome = "EXECUTED";
            long gatewayOverheadMs = Math.max(0L, (System.nanoTime() - startNanos - downstreamNanos) / 1_000_000);
            log.info("Governed tool call executed: outcome={}, rule={}@v{}, gatewayOverheadMs={}",
                    outcome, policyRuleName, policyRuleVersion, gatewayOverheadMs);

            return new GatewayToolCallResponse(
                    callId,
                    traceId,
                    tool.getName(),
                    outcome,
                    policyRuleName,
                    policyRuleVersion,
                    null,
                    null,
                    sanitizedPayload,
                    gatewayOverheadMs,
                    Instant.now()
            );

        } catch (GuardRejectedException ex) {
            outcome = "REJECTED_BY_GUARD";
            policyRuleName = ex.getGuardName();
            log.warn("Tool call rejected by guard {}: {}", ex.getGuardName(), ex.getMessage());
            throw ex;
        } catch (LimitExceededException ex) {
            outcome = "RATE_LIMITED";
            policyRuleName = ex.getBreachType().name();
            log.warn("Tool call blocked by limit {}: {}", ex.getBreachType(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            if ("UNKNOWN".equals(outcome)) {
                outcome = "BLOCKED_OR_FAILED";
            }
            log.warn("Tool call blocked/failed: outcome={}, reason={}", outcome, ex.getMessage());
            throw ex;
        } finally {
            long totalNanos = System.nanoTime() - startNanos;
            long overheadNanos = Math.max(0L, totalNanos - downstreamNanos);
            long totalLatencyMs = totalNanos / 1_000_000;

            Timer.builder("agentgate.gateway.overhead")
                    .description("Gateway governance processing overhead excluding downstream execution (QA2)")
                    .tag("tool", request.toolName())
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .record(overheadNanos, TimeUnit.NANOSECONDS);
            meterRegistry.counter("agentgate.gateway.calls",
                    "tool", request.toolName(),
                    "outcome", outcome).increment();

            eventPublisher.publishEvent(new ToolCallAuditRecordedEvent(
                    callId,
                    traceId,
                    authenticatedClientId,
                    request.delegatedUserId(),
                    request.toolName(),
                    redactedArgsJson,
                    policyRuleName,
                    policyRuleVersion,
                    approvalId,
                    approvalState,
                    outcome,
                    totalLatencyMs,
                    Instant.now()
            ));

            MDC.remove("traceId");
            MDC.remove("callId");
            MDC.remove("agentClientId");
            MDC.remove("toolName");
        }
    }

    /**
     * FR4: Returns the current or final result of a paused high-risk call to the agent that made it,
     * so the agent receives a clear outcome (executed, failed, rejected, timed out or cancelled).
     */
    public AgentApprovalResultResponse getApprovalResultForAgent(String authenticatedClientId, UUID approvalId) {
        return AgentApprovalResultResponse.from(
                approvalWorkflowService.getApprovalForRequestingAgent(approvalId, authenticatedClientId));
    }

    /**
     * Tells the agent where to poll for the final result of a call that is held for approval (FR4).
     */
    private static String pendingApprovalPayload(UUID approvalId, Instant expiresAt) {
        return "{\"status\":\"PENDING_APPROVAL\",\"approvalId\":\"" + approvalId
                + "\",\"statusUrl\":\"" + APPROVAL_STATUS_PATH + approvalId
                + "\",\"expiresAt\":\"" + expiresAt + "\"}";
    }

    private String resolveTraceId(UUID fallbackCallId) {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer != null) {
            Span span = tracer.currentSpan();
            if (span != null && span.context() != null && span.context().traceId() != null) {
                return span.context().traceId();
            }
        }
        return fallbackCallId.toString().replace("-", "");
    }
}
