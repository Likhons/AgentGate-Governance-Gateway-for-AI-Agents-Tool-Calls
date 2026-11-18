package com.cse444.agentgate.guard.service;

import com.cse444.agentgate.guard.domain.LimitBreachAlert;
import com.cse444.agentgate.guard.domain.LimitBreachAlertRepository;
import com.cse444.agentgate.guard.dto.LimitBreachAlertResponse;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.event.LimitBreachAlertEvent;
import com.cse444.agentgate.shared.event.LimitBreachAlertEvent.BreachType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FR6: "A breach blocks further calls and raises an alert."
 * Design Pattern: Observer, listening to the {@link LimitBreachAlertEvent} that
 * {@code RateLimitAndBlastRadiusGuard} publishes when it blocks a call.
 * <p>
 * At most one alert per agent, tool and breach type is raised per {@code agentgate.limits.alert-cooldown},
 * so a flood of blocked calls produces one alert instead of thousands; every blocked call is still audited
 * and counted in {@code agentgate.limits.breaches}. Each alert is recorded (listed at
 * {@code GET /api/v1/limits/alerts}) and posted to {@code agentgate.limits.alert-webhook-url} when set.
 * The work runs on a virtual thread, so alerting never slows down or fails the blocked call, including
 * during a database outage (QA4).
 */
@Service
public class LimitBreachAlertService {

    private record AlertKey(String agentClientId, String toolName, BreachType breachType) {}

    private static final Logger log = LoggerFactory.getLogger(LimitBreachAlertService.class);

    private final LimitBreachAlertRepository alertRepository;
    private final AgentGateProperties properties;
    private final RestClient restClient;
    private final ExecutorService alertExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<AlertKey, Instant> lastAlertAt = new ConcurrentHashMap<>();

    public LimitBreachAlertService(
            LimitBreachAlertRepository alertRepository,
            AgentGateProperties properties,
            RestClient.Builder restClientBuilder
    ) {
        this.alertRepository = alertRepository;
        this.properties = properties;
        // Same delivery timeout as the approver webhooks (agentgate.approval.webhook-timeout)
        Duration timeout = properties.approval().webhookTimeout();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = restClientBuilder.clone().requestFactory(requestFactory).build();
    }

    @EventListener
    public void onLimitBreach(LimitBreachAlertEvent event) {
        Instant now = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        Duration cooldown = properties.limits().alertCooldown();
        AtomicBoolean raise = new AtomicBoolean(false);
        lastAlertAt.compute(new AlertKey(event.agentClientId(), event.toolName(), event.breachType()), (key, previous) -> {
            if (previous == null || Duration.between(previous, now).compareTo(cooldown) >= 0) {
                raise.set(true);
                return now;
            }
            return previous;
        });
        if (raise.get()) {
            alertExecutor.execute(() -> raiseAlert(event, now));
        }
    }

    @Transactional(readOnly = true)
    public Page<LimitBreachAlertResponse> listAlerts(Pageable pageable) {
        return alertRepository.findAllByOrderByRaisedAtDesc(pageable).map(LimitBreachAlertResponse::from);
    }

    @PreDestroy
    void shutdown() {
        alertExecutor.shutdown();
    }

    private void raiseAlert(LimitBreachAlertEvent event, Instant raisedAt) {
        String configuredUrl = properties.limits().alertWebhookUrl();
        String webhookUrl = (configuredUrl == null || configuredUrl.isBlank()) ? null : configuredUrl.trim();
        log.warn("[LIMIT BREACH ALERT RAISED] agent='{}' tool='{}' type={} limit={}",
                event.agentClientId(), event.toolName(), event.breachType(), event.configuredLimit());
        try {
            alertRepository.save(new LimitBreachAlert(
                    event.agentClientId(),
                    event.delegatedUserId(),
                    event.toolName(),
                    event.breachType(),
                    event.configuredLimit(),
                    event.details(),
                    webhookUrl,
                    raisedAt
            ));
        } catch (RuntimeException ex) {
            log.warn("Could not record the limit-breach alert (the webhook is still sent): {}", ex.getMessage());
        }
        if (webhookUrl != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "limit.breach");
            payload.put("agentClientId", event.agentClientId());
            payload.put("delegatedUserId", event.delegatedUserId());
            payload.put("toolName", event.toolName());
            payload.put("breachType", event.breachType().name());
            payload.put("configuredLimit", event.configuredLimit());
            payload.put("message", event.details());
            payload.put("raisedAt", raisedAt.toString());
            try {
                restClient.post()
                        .uri(webhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception ex) {
                log.warn("Limit-breach alert webhook to {} failed: {}", webhookUrl, ex.getMessage());
            }
        }
    }
}
