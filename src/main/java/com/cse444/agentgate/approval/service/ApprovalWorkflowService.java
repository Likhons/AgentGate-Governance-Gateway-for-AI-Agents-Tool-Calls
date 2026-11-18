package com.cse444.agentgate.approval.service;

import com.cse444.agentgate.approval.domain.ApprovalRequest;
import com.cse444.agentgate.approval.domain.ApprovalRequestRepository;
import com.cse444.agentgate.approval.dto.ApprovalDecisionRequest;
import com.cse444.agentgate.approval.dto.ApprovalRequestResponse;
import com.cse444.agentgate.registry.adapter.DownstreamToolAdapter.DownstreamExecutionResult;
import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.domain.ApprovalStateType;
import com.cse444.agentgate.shared.domain.ClearanceLevel;
import com.cse444.agentgate.shared.event.KillSwitchActivatedEvent;
import com.cse444.agentgate.shared.event.ToolCallAuditRecordedEvent;
import com.cse444.agentgate.shared.util.PiiSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the Human-in-the-Loop Approval State Machine (FR4) and
 * Kill-Switch cancellation listener (FR8).
 */
@Service
public class ApprovalWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflowService.class);

    private final ApprovalRequestRepository approvalRepository;
    private final ToolRegistryService toolRegistryService;
    private final ApprovalWebhookNotifier webhookNotifier;
    private final AgentGateProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ApprovalWorkflowService(
            ApprovalRequestRepository approvalRepository,
            ToolRegistryService toolRegistryService,
            ApprovalWebhookNotifier webhookNotifier,
            AgentGateProperties properties,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.approvalRepository = approvalRepository;
        this.toolRegistryService = toolRegistryService;
        this.webhookNotifier = webhookNotifier;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Pauses a high-risk tool call as a PENDING approval request and notifies the approver (FR4).
     */
    @Transactional
    public ApprovalRequestResponse createPendingApproval(
            String agentClientId,
            String delegatedUserId,
            RegisteredTool tool,
            Map<String, Object> arguments,
            String policyRuleName,
            int policyRuleVersion,
            String webhookUrl,
            Duration customTimeout
    ) {
        Duration timeout = (customTimeout != null && !customTimeout.isZero() && !customTimeout.isNegative())
                ? customTimeout
                : properties.approval().defaultTimeout();
        Instant expiresAt = Instant.now().plus(timeout);

        String argsJson = serializeArguments(arguments);
        ApprovalRequest request = new ApprovalRequest(
                UUID.randomUUID(),
                agentClientId,
                delegatedUserId,
                tool.getName(),
                tool.isWriteOperation(),
                argsJson,
                policyRuleName,
                policyRuleVersion,
                webhookUrl,
                expiresAt
        );
        ApprovalRequest saved = approvalRepository.save(request);
        webhookNotifier.notifyApproverWebhookAsync(saved);
        return ApprovalRequestResponse.from(saved);
    }

    /**
     * Approves a pending request (with optional edited arguments) and executes the downstream tool (FR4).
     * A downstream failure is recorded on the request (executionStatus = FAILED) instead of rolling the
     * decision back, so the agent and approver always see a definite final result.
     */
    @Transactional
    public ApprovalRequestResponse approveRequest(
            UUID approvalId,
            ApprovalDecisionRequest decision,
            String approverId
    ) {
        ApprovalRequest request = getRequiredApproval(approvalId);
        checkNotExpired(request);

        if (decision != null && decision.editedArguments() != null && !decision.editedArguments().isEmpty()) {
            String editedJson = serializeArguments(decision.editedArguments());
            request.approveWithEditedArguments(approverId, editedJson, decision.note());
        } else {
            request.approve(approverId, decision != null ? decision.note() : null);
        }

        RegisteredTool tool = toolRegistryService.getRequiredToolByName(request.getToolName());

        // Flush the decision before the side effect: the @Version optimistic lock makes a concurrent
        // second decision on the same request fail here, so a payout or refund can never run twice.
        approvalRepository.saveAndFlush(request);

        String auditOutcome;
        long downstreamLatencyMs = 0L;
        try {
            Map<String, Object> effectiveArgs = parseArguments(request.getEffectiveArgumentsJson());
            DownstreamExecutionResult execResult = toolRegistryService.executeDownstreamTool(tool, effectiveArgs);
            request.recordExecutionSucceeded(PiiSanitizer.redactByClearance(execResult.payload(), ClearanceLevel.NONE));
            downstreamLatencyMs = execResult.latencyMs();
            auditOutcome = "APPROVED_EXECUTED";
        } catch (RuntimeException ex) {
            log.warn("Approved request {} for tool '{}' failed downstream: {}",
                    request.getId(), request.getToolName(), ex.getMessage());
            request.recordExecutionFailed(executionFailurePayload(ex));
            auditOutcome = "APPROVED_EXECUTION_FAILED";
        }

        ApprovalRequest saved = approvalRepository.save(request);
        publishApprovalAuditEvent(saved, auditOutcome, downstreamLatencyMs);
        return ApprovalRequestResponse.from(saved);
    }

    /**
     * Rejects a pending approval request (FR4).
     */
    @Transactional
    public ApprovalRequestResponse rejectRequest(UUID approvalId, String reason, String approverId) {
        ApprovalRequest request = getRequiredApproval(approvalId);
        checkNotExpired(request);
        request.reject(approverId, reason);
        ApprovalRequest saved = approvalRepository.save(request);
        publishApprovalAuditEvent(saved, "APPROVAL_REJECTED", 0L);
        return ApprovalRequestResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ApprovalRequestResponse getApprovalStatus(UUID approvalId) {
        return ApprovalRequestResponse.from(getRequiredApproval(approvalId));
    }

    /**
     * FR4: Lets the calling agent read the current or final result of its own paused call.
     * Requests that belong to another agent are reported as not found, so their existence is not leaked.
     */
    @Transactional(readOnly = true)
    public ApprovalRequestResponse getApprovalForRequestingAgent(UUID approvalId, String agentClientId) {
        return approvalRepository.findById(approvalId)
                .filter(found -> found.getAgentClientId().equals(agentClientId))
                .map(ApprovalRequestResponse::from)
                .orElseThrow(() -> new EntityNotFoundException("Approval request not found: " + approvalId));
    }

    @Transactional(readOnly = true)
    public Page<ApprovalRequestResponse> listApprovals(ApprovalStateType stateFilter, Pageable pageable) {
        if (stateFilter != null) {
            return approvalRepository.findAllByStateOrderByCreatedAtDesc(stateFilter, pageable)
                    .map(ApprovalRequestResponse::from);
        }
        return approvalRepository.findAll(pageable).map(ApprovalRequestResponse::from);
    }

    /**
     * FR4 Timeout Sweeper: Automatically rejects unanswered requests after their timeout expires.
     */
    @Scheduled(fixedDelayString = "5000")
    @Transactional
    public int expireTimedOutApprovals() {
        List<ApprovalRequest> expired = approvalRepository.findAllByStateAndExpiresAtBefore(
                ApprovalStateType.PENDING,
                Instant.now()
        );
        for (ApprovalRequest req : expired) {
            req.timeout();
        }
        if (!expired.isEmpty()) {
            approvalRepository.saveAll(expired);
            expired.forEach(req -> publishApprovalAuditEvent(req, "APPROVAL_TIMED_OUT", 0L));
            log.info("Expired {} unanswered approval requests on timeout (FR4)", expired.size());
        }
        return expired.size();
    }

    /**
     * FR8 Kill-Switch Observer: Immediately cancels pending approvals when an agent,
     * a tool, or all write tools are disabled.
     */
    @EventListener
    @Transactional
    public void onKillSwitchActivated(KillSwitchActivatedEvent event) {
        List<ApprovalRequest> affected = switch (event.scope()) {
            case AGENT -> approvalRepository.findAllByStateAndAgentClientId(
                    ApprovalStateType.PENDING, event.targetIdentifier());
            case TOOL -> approvalRepository.findAllByStateAndToolName(
                    ApprovalStateType.PENDING, event.targetIdentifier());
            case ALL_WRITE_TOOLS -> approvalRepository.findAllByStateAndWriteOperationTrue(
                    ApprovalStateType.PENDING);
        };

        for (ApprovalRequest req : affected) {
            req.cancelByKillSwitch(event.reason());
        }
        if (!affected.isEmpty()) {
            approvalRepository.saveAll(affected);
            affected.forEach(req -> publishApprovalAuditEvent(req, "APPROVAL_CANCELLED_BY_KILL_SWITCH", 0L));
            log.info("Cancelled {} pending approvals due to {} Kill Switch on '{}' (FR8)",
                    affected.size(), event.scope(), event.targetIdentifier());
        }
    }

    private void publishApprovalAuditEvent(ApprovalRequest req, String outcome, long latencyMs) {
        String redactedArgs = PiiSanitizer.redactByClearance(req.getEffectiveArgumentsJson(), ClearanceLevel.NONE);
        eventPublisher.publishEvent(new ToolCallAuditRecordedEvent(
                UUID.randomUUID(),
                req.getId().toString().replace("-", ""),
                req.getAgentClientId(),
                req.getDelegatedUserId(),
                req.getToolName(),
                redactedArgs,
                req.getPolicyRuleName(),
                req.getPolicyRuleVersion(),
                req.getId(),
                req.getState().name(),
                outcome,
                latencyMs,
                Instant.now()
        ));
    }

    private String executionFailurePayload(RuntimeException ex) {
        String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("error", "DOWNSTREAM_EXECUTION_FAILED");
        failure.put("message", PiiSanitizer.redactByClearance(reason, ClearanceLevel.NONE));
        return serializeArguments(failure);
    }

    private void checkNotExpired(ApprovalRequest request) {
        if (request.getState() == ApprovalStateType.PENDING && Instant.now().isAfter(request.getExpiresAt())) {
            request.timeout();
            approvalRepository.save(request);
            throw new IllegalStateException("Approval request " + request.getId() + " has timed out and was automatically rejected (FR4)");
        }
    }

    private ApprovalRequest getRequiredApproval(UUID approvalId) {
        return approvalRepository.findById(approvalId)
                .orElseThrow(() -> new EntityNotFoundException("Approval request not found: " + approvalId));
    }

    private String serializeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments != null ? arguments : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseArguments(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
