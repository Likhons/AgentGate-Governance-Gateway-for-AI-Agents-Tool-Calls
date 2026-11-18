package com.cse444.agentgate.approval.service;

import com.cse444.agentgate.approval.domain.ApprovalRequest;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.domain.ClearanceLevel;
import com.cse444.agentgate.shared.util.PiiSanitizer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends approver webhooks asynchronously on Java 21 virtual threads (FR4, FR8):
 * <ul>
 *   <li>{@code approval.pending} when a high-risk call pauses, and</li>
 *   <li>{@code approval.resolved} when it is approved, rejected, timed out or cancelled by a kill switch.</li>
 * </ul>
 * The target URL comes from the tool registration (or {@code agentgate.approval.default-webhook-url}),
 * never from the calling agent, so a prompt-injected agent cannot exfiltrate arguments or make the
 * gateway call arbitrary hosts. Arguments are PII-redacted, delivery honours
 * {@code agentgate.approval.webhook-timeout}, and webhooks are sent only after the surrounding
 * transaction commits so an approver is never told about a decision that was rolled back.
 */
@Component
public class ApprovalWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWebhookNotifier.class);

    private final RestClient restClient;
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ApprovalWebhookNotifier(RestClient.Builder restClientBuilder, AgentGateProperties properties) {
        Duration timeout = properties.approval().webhookTimeout();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        // clone() so the webhook timeout never leaks into the shared builder used by downstream adapters
        this.restClient = restClientBuilder.clone().requestFactory(requestFactory).build();
    }

    public void notifyApprovalPending(ApprovalRequest request) {
        Map<String, Object> payload = basePayload("approval.pending", request);
        payload.put("arguments", PiiSanitizer.redactByClearance(request.getOriginalArgumentsJson(), ClearanceLevel.NONE));
        payload.put("expiresAt", request.getExpiresAt().toString());
        payload.put("approvalConsoleUrl", "/approvals/console");
        dispatchAfterCommit(request.getWebhookUrl(), payload, request.getId());
    }

    public void notifyApprovalResolved(ApprovalRequest request) {
        Map<String, Object> payload = basePayload("approval.resolved", request);
        payload.put("state", request.getState().name());
        payload.put("executionStatus", request.getExecutionStatus() != null ? request.getExecutionStatus().name() : null);
        payload.put("decidedBy", request.getApproverId());
        payload.put("decisionNote", request.getDecisionNote());
        payload.put("decidedAt", request.getDecidedAt() != null ? request.getDecidedAt().toString() : null);
        dispatchAfterCommit(request.getWebhookUrl(), payload, request.getId());
    }

    @PreDestroy
    void shutdown() {
        virtualExecutor.shutdown();
    }

    private Map<String, Object> basePayload(String event, ApprovalRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("approvalId", request.getId().toString());
        payload.put("agentClientId", request.getAgentClientId());
        payload.put("delegatedUserId", request.getDelegatedUserId());
        payload.put("toolName", request.getToolName());
        payload.put("policyRule", request.getPolicyRuleName() + "@v" + request.getPolicyRuleVersion());
        return payload;
    }

    private void dispatchAfterCommit(String webhookUrl, Map<String, Object> payload, UUID approvalId) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        Runnable send = () -> virtualExecutor.execute(() -> deliver(webhookUrl, payload, approvalId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void deliver(String webhookUrl, Map<String, Object> payload, UUID approvalId) {
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Approval webhook '{}' to {} failed for approval {}: {}",
                    payload.get("event"), webhookUrl, approvalId, ex.getMessage());
        }
    }
}
