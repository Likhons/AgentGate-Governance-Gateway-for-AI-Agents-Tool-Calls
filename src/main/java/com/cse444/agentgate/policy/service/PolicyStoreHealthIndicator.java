package com.cse444.agentgate.policy.service;

import com.cse444.agentgate.shared.config.AgentGateProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Shows the QA4 fail-safe mode as the {@code policyStore} component of {@code /actuator/health} (C4):
 * UP while the policy store answers; DOWN while MEDIUM/HIGH-risk tools fail closed and LOW-risk
 * tools run on the last known policies, with the snapshot age so operators can see how close the
 * 5-minute fail-safe limit is.
 */
@Component("policyStore")
public class PolicyStoreHealthIndicator implements HealthIndicator {

    private final PolicyEngineService policyEngineService;
    private final AgentGateProperties properties;

    public PolicyStoreHealthIndicator(PolicyEngineService policyEngineService, AgentGateProperties properties) {
        this.policyEngineService = policyEngineService;
        this.properties = properties;
    }

    @Override
    public Health health() {
        long snapshotAgeSeconds = policyEngineService.getSnapshotAge().toSeconds();
        if (policyEngineService.isPolicyStoreReachable()) {
            return Health.up()
                    .withDetail("snapshotAgeSeconds", snapshotAgeSeconds)
                    .build();
        }
        return Health.down()
                .withDetail("mode", "FAIL_SAFE: MEDIUM/HIGH-risk tools fail closed; LOW-risk tools use the last known policies")
                .withDetail("snapshotAgeSeconds", snapshotAgeSeconds)
                .withDetail("lowRiskFailsafeTtlSeconds", properties.policy().lowRiskFailsafeTtl().toSeconds())
                .build();
    }
}
