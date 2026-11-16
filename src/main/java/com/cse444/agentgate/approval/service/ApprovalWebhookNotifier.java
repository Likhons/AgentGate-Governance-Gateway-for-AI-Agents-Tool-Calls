package com.cse444.agentgate.approval.service;

import com.cse444.agentgate.approval.domain.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends asynchronous Webhook notifications on Java 21 Virtual Threads when a high-risk
 * tool call pauses for human approval (FR4).
 */
@Component
public class ApprovalWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWebhookNotifier.class);

    private final RestClient restClient;
    private final ExecutorService virtualExecutor;

    public ApprovalWebhookNotifier(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void notifyApproverWebhookAsync(ApprovalRequest request) {
        if (request.getWebhookUrl() == null || request.getWebhookUrl().isBlank()) {
            return;
        }
        virtualExecutor.submit(() -> {
            try {
                Map<String, Object> payload = Map.of(
                        "event", "approval.pending",
                        "approvalId", request.getId().toString(),
                        "agentClientId", request.getAgentClientId(),
                        "delegatedUserId", request.getDelegatedUserId() != null ? request.getDelegatedUserId() : "",
                        "toolName", request.getToolName(),
                        "arguments", request.getOriginalArgumentsJson(),
                        "expiresAt", request.getExpiresAt().toString(),
                        "approvalConsoleUrl", "/approvals/console"
                );
                restClient.post()
                        .uri(request.getWebhookUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception ex) {
                log.warn("Webhook delivery to {} failed for approval {}: {}",
                        request.getWebhookUrl(), request.getId(), ex.getMessage());
            }
        });
    }
}
