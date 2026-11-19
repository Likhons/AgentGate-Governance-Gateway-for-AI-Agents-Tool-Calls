package com.cse444.agentgate.policy.service;

import com.cse444.agentgate.policy.domain.PolicyCondition;
import com.cse444.agentgate.policy.domain.PolicyRule;
import com.cse444.agentgate.policy.domain.PolicyRuleRepository;
import com.cse444.agentgate.policy.dto.CreatePolicyRuleRequest;
import com.cse444.agentgate.policy.dto.PolicyEvaluationDecision;
import com.cse444.agentgate.policy.dto.PolicyPlaygroundRequest;
import com.cse444.agentgate.policy.dto.PolicyRuleResponse;
import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.domain.PolicyEffect;
import com.cse444.agentgate.shared.domain.RiskLevel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core Policy Engine implementing:
 * - Versioned allow/deny rules with default-deny stance (FR3)
 * - 10-second hot-reload into an atomic in-memory snapshot without restart (FR3, QA2)
 * - Dry-run mode logging what would have been blocked without blocking (FR3)
 * - Fail-safe availability: LOW-risk tools work from last known snapshot up to 5 minutes during
 *   policy store outage, while MEDIUM and HIGH-risk tools fail closed immediately (QA4)
 * - Policy Playground for testing sample calls against current or draft rules (FR9)
 *
 * Design Patterns: Chain of Responsibility & Strategy Pattern.
 */
@Service
public class PolicyEngineService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngineService.class);

    private final PolicyRuleRepository policyRepository;
    private final PolicyConditionEvaluator conditionEvaluator;
    private final ToolRegistryService toolRegistryService;
    private final AgentGateProperties properties;
    private final ObjectMapper objectMapper;

    private final AtomicReference<PolicySnapshot> activeSnapshot =
            new AtomicReference<>(new PolicySnapshot(List.of(), Instant.now()));
    private final AtomicBoolean policyStoreReachable = new AtomicBoolean(true);
    /** Outage drills and tests only; kept apart from the probed state so the probe cannot undo a drill. */
    private final AtomicBoolean simulatedOutage = new AtomicBoolean(false);
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);
    private final ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param conditionsUnreadable the stored conditions could not be read or are incomplete; a call that reaches
     *                             such a rule is denied instead of the rule acting as if it had no conditions (FR3)
     */
    public record CompiledPolicyRule(
            PolicyRule rule,
            List<PolicyCondition> conditions,
            boolean conditionsUnreadable
    ) {}

    public record PolicySnapshot(
            List<CompiledPolicyRule> rules,
            Instant loadedAt
    ) {}

    public PolicyEngineService(
            PolicyRuleRepository policyRepository,
            PolicyConditionEvaluator conditionEvaluator,
            ToolRegistryService toolRegistryService,
            AgentGateProperties properties,
            ObjectMapper objectMapper
    ) {
        this.policyRepository = policyRepository;
        this.conditionEvaluator = conditionEvaluator;
        this.toolRegistryService = toolRegistryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * FR3: Load persisted active policies immediately on startup and refresh every 10 seconds
     * into an atomic in-memory snapshot without requiring a restart.
     */
    @PostConstruct
    @Scheduled(fixedDelayString = "${agentgate.policy.refresh-interval:10s}")
    public void refreshPolicySnapshot() {
        try {
            List<PolicyRule> activeRules = policyRepository.findAllByActiveTrueOrderByPriorityAscVersionDesc();
            List<CompiledPolicyRule> compiled = activeRules.stream()
                    .map(this::compile)
                    .toList();
            activeSnapshot.set(new PolicySnapshot(compiled, Instant.now()));
            if (!policyStoreReachable.getAndSet(true)) {
                log.info("Policy store reachable again; policy snapshot reloaded (QA4)");
            }
        } catch (Exception ex) {
            markPolicyStoreUnreachable("hot-reload failed: " + ex.getMessage());
        }
    }

    /**
     * QA4: Detects a policy store (PostgreSQL) outage within seconds, independently of the 10 s policy
     * refresh, so MEDIUM/HIGH-risk tools fail closed promptly. The probe runs on a virtual thread and is
     * bounded by {@code agentgate.policy.store-probe-timeout}, so a hung connection also counts as an outage.
     */
    @Scheduled(fixedDelayString = "${agentgate.policy.store-probe-interval:2s}")
    public void probePolicyStore() {
        if (!probeInFlight.compareAndSet(false, true)) {
            markPolicyStoreUnreachable("previous probe is still waiting for the database");
            return;
        }
        Future<Long> probe = probeExecutor.submit(() -> {
            try {
                return policyRepository.count();
            } finally {
                probeInFlight.set(false);
            }
        });
        try {
            probe.get(properties.policy().storeProbeTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!policyStoreReachable.get()) {
                refreshPolicySnapshot();
            }
        } catch (TimeoutException ex) {
            markPolicyStoreUnreachable("probe timed out after " + properties.policy().storeProbeTimeout());
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            markPolicyStoreUnreachable("probe failed: " + cause.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * QA4: true while the policy store answers; false during an outage (or an outage drill).
     */
    public boolean isPolicyStoreReachable() {
        return policyStoreReachable.get() && !simulatedOutage.get();
    }

    /**
     * Age of the in-memory policy snapshot; LOW-risk tools stop when it exceeds the fail-safe TTL (QA4).
     */
    public Duration getSnapshotAge() {
        return Duration.between(activeSnapshot.get().loadedAt(), Instant.now());
    }

    private void markPolicyStoreUnreachable(String reason) {
        if (policyStoreReachable.getAndSet(false)) {
            log.warn("Policy store unreachable ({}); MEDIUM/HIGH-risk tools fail closed and LOW-risk tools "
                    + "use the last known policies for up to {} (QA4)", reason, properties.policy().lowRiskFailsafeTtl());
        }
    }

    /**
     * Creates a new versioned policy rule, automatically incrementing the version number
     * and deactivating prior active versions of the same ruleName (FR3).
     */
    @Transactional
    public PolicyRuleResponse createOrVersionPolicyRule(CreatePolicyRuleRequest request, String author) {
        int nextVersion = policyRepository.findMaxVersionByRuleName(request.ruleName()) + 1;
        List<PolicyRule> previousActive = policyRepository.findAllByRuleNameAndActiveTrue(request.ruleName());
        previousActive.forEach(PolicyRule::deactivate);
        policyRepository.saveAll(previousActive);

        String conditionsJson = serializeConditions(request.conditions());
        PolicyRule newRule = new PolicyRule(
                UUID.randomUUID(),
                request.ruleName(),
                nextVersion,
                request.priority(),
                request.agentPattern(),
                request.toolPattern(),
                request.effect(),
                conditionsJson,
                request.dryRun(),
                true,
                request.description(),
                author
        );
        PolicyRule saved = policyRepository.save(newRule);
        refreshPolicySnapshot();
        return PolicyRuleResponse.from(saved, request.conditions() != null ? request.conditions() : List.of());
    }

    @Transactional(readOnly = true)
    public Page<PolicyRuleResponse> listPolicyRules(Pageable pageable) {
        return policyRepository.findAllByOrderByPriorityAscVersionDesc(pageable)
                .map(rule -> PolicyRuleResponse.from(rule, parseConditions(rule.getConditionsJson())));
    }

    /**
     * Evaluates a live tool call against the hot-reloaded policy snapshot, enforcing QA4 fail-safe rules.
     */
    public PolicyEvaluationDecision evaluateLiveToolCall(
            String agentClientId,
            String delegatedUserId,
            RegisteredTool tool,
            Map<String, Object> arguments
    ) {
        PolicySnapshot snapshot = activeSnapshot.get();
        boolean storeHealthy = isPolicyStoreReachable();

        // QA4 Fail-Safe Availability Check when the policy store is unreachable
        if (!storeHealthy) {
            if (tool.getRiskLevel() == RiskLevel.MEDIUM || tool.getRiskLevel() == RiskLevel.HIGH) {
                return new PolicyEvaluationDecision(
                        false,
                        PolicyEffect.DENY,
                        null,
                        "QA4_FAIL_CLOSED_HIGH_OR_MEDIUM_RISK",
                        0,
                        false,
                        null,
                        false,
                        "Policy store is unreachable; " + tool.getRiskLevel() + "-risk tools fail closed (QA4)",
                        Instant.now()
                );
            }
            Duration snapshotAge = Duration.between(snapshot.loadedAt(), Instant.now());
            if (snapshotAge.compareTo(properties.policy().lowRiskFailsafeTtl()) > 0) {
                return new PolicyEvaluationDecision(
                        false,
                        PolicyEffect.DENY,
                        null,
                        "QA4_FAIL_CLOSED_CACHE_TTL_EXPIRED",
                        0,
                        false,
                        null,
                        false,
                        "Policy store unreachable for longer than 5 minutes; LOW-risk fail-safe cache expired (QA4)",
                        Instant.now()
                );
            }
            return evaluateAgainstRules(
                    agentClientId,
                    delegatedUserId,
                    tool.getName(),
                    arguments,
                    snapshot.rules(),
                    true
            );
        }

        return evaluateAgainstRules(
                agentClientId,
                delegatedUserId,
                tool.getName(),
                arguments,
                snapshot.rules(),
                false
        );
    }

    /**
     * FR9 Policy Playground: Evaluates a sample call against either inline draft rules
     * or the currently active policy snapshot without side effects.
     */
    public PolicyEvaluationDecision simulateInPlayground(PolicyPlaygroundRequest request) {
        List<CompiledPolicyRule> rulesToTest;
        if (request.draftRules() != null && !request.draftRules().isEmpty()) {
            rulesToTest = request.draftRules().stream()
                    .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                    .map(draft -> new CompiledPolicyRule(
                            new PolicyRule(
                                    UUID.randomUUID(),
                                    draft.ruleName(),
                                    1,
                                    draft.priority(),
                                    draft.agentPattern(),
                                    draft.toolPattern(),
                                    draft.effect(),
                                    serializeConditions(draft.conditions()),
                                    draft.dryRun(),
                                    true,
                                    draft.description(),
                                    "playground-draft"
                            ),
                            draft.conditions() != null ? draft.conditions() : List.of(),
                            false
                    ))
                    .toList();
        } else {
            rulesToTest = activeSnapshot.get().rules();
        }

        return evaluateAgainstRules(
                request.agentClientId(),
                request.delegatedUserId(),
                request.toolName(),
                request.arguments() != null ? request.arguments() : Map.of(),
                rulesToTest,
                false
        );
    }

    /**
     * Allows tests or outage drills to simulate policy-store unreachability (QA4).
     */
    public void setSimulatedPolicyStoreReachable(boolean reachable) {
        this.simulatedOutage.set(!reachable);
    }

    /**
     * FR1: Whether the agent may call the tool under the active policies, i.e. a matching ALLOW rule exists
     * (possibly with argument conditions) and no unconditional DENY rule takes precedence. Used to show an
     * agent only the tools it is permitted to call; every call is still evaluated in full.
     * Mirrors {@link #evaluateAgainstRules}: dry-run DENY rules never block.
     */
    public boolean isToolPermittedForAgent(String agentClientId, String toolName) {
        for (CompiledPolicyRule compiled : activeSnapshot.get().rules()) {
            PolicyRule rule = compiled.rule();
            if (!matchesPattern(rule.getAgentPattern(), agentClientId) || !matchesPattern(rule.getToolPattern(), toolName)) {
                continue;
            }
            if (compiled.conditionsUnreadable()) {
                if (rule.isDryRun() && rule.getEffect() == PolicyEffect.DENY) {
                    continue;
                }
                return false;
            }
            if (rule.getEffect() == PolicyEffect.ALLOW) {
                return true;
            }
            if (!rule.isDryRun() && compiled.conditions().isEmpty()) {
                return false;
            }
        }
        return false;
    }

    private PolicyEvaluationDecision evaluateAgainstRules(
            String agentClientId,
            String delegatedUserId,
            String toolName,
            Map<String, Object> arguments,
            List<CompiledPolicyRule> rules,
            boolean fromFailsafeCache
    ) {
        String dryRunBlockedByRule = null;

        for (CompiledPolicyRule compiled : rules) {
            PolicyRule rule = compiled.rule();
            if (!matchesPattern(rule.getAgentPattern(), agentClientId)) {
                continue;
            }
            if (!matchesPattern(rule.getToolPattern(), toolName)) {
                continue;
            }

            // FR3: a rule whose stored conditions cannot be read fails closed; it must never act as unconditional.
            // A dry-run DENY rule only logs, so it still never blocks.
            if (compiled.conditionsUnreadable()) {
                if (rule.isDryRun() && rule.getEffect() == PolicyEffect.DENY) {
                    continue;
                }
                return new PolicyEvaluationDecision(
                        false,
                        PolicyEffect.DENY,
                        rule.getId(),
                        rule.getRuleName(),
                        rule.getVersion(),
                        dryRunBlockedByRule != null,
                        dryRunBlockedByRule,
                        fromFailsafeCache,
                        "Policy rule '" + rule.getRuleName() + "' (v" + rule.getVersion()
                                + ") has unreadable conditions; failing closed (FR3)",
                        Instant.now()
                );
            }

            boolean conditionsMatch;
            try {
                conditionsMatch = conditionEvaluator.matchesAll(compiled.conditions(), arguments, delegatedUserId);
            } catch (IllegalArgumentException ex) {
                return new PolicyEvaluationDecision(
                        false,
                        PolicyEffect.DENY,
                        rule.getId(),
                        rule.getRuleName(),
                        rule.getVersion(),
                        false,
                        null,
                        fromFailsafeCache,
                        "Argument condition error on rule '" + rule.getRuleName() + "': " + ex.getMessage(),
                        Instant.now()
                );
            }

            if (!conditionsMatch) {
                continue;
            }

            // FR3 Dry-Run Mode: A dry-run DENY rule only logs what would be blocked and continues evaluation
            if (rule.isDryRun() && rule.getEffect() == PolicyEffect.DENY) {
                dryRunBlockedByRule = rule.getRuleName() + "@v" + rule.getVersion();
                log.info("[DRY-RUN POLICY] Call by agent='{}' to tool='{}' WOULD BE BLOCKED by rule '{}'",
                        agentClientId, toolName, dryRunBlockedByRule);
                continue;
            }

            boolean allowed = rule.getEffect() == PolicyEffect.ALLOW;
            return new PolicyEvaluationDecision(
                    allowed,
                    rule.getEffect(),
                    rule.getId(),
                    rule.getRuleName(),
                    rule.getVersion(),
                    dryRunBlockedByRule != null,
                    dryRunBlockedByRule,
                    fromFailsafeCache,
                    (allowed ? "Allowed" : "Denied") + " by policy rule '" + rule.getRuleName() + "' (v" + rule.getVersion() + ")",
                    Instant.now()
            );
        }

        // FR3 Default Stance: DENY when no rule matches
        return new PolicyEvaluationDecision(
                false,
                PolicyEffect.DENY,
                null,
                "DEFAULT_DENY",
                0,
                dryRunBlockedByRule != null,
                dryRunBlockedByRule,
                fromFailsafeCache,
                "Denied by default-deny policy stance (no matching ALLOW rule found)",
                Instant.now()
        );
    }

    private boolean matchesPattern(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return value != null && value.startsWith(prefix);
        }
        return pattern.equals(value);
    }

    private CompiledPolicyRule compile(PolicyRule rule) {
        try {
            return new CompiledPolicyRule(rule, readConditions(rule.getConditionsJson()), false);
        } catch (Exception ex) {
            log.error("Policy rule '{}' (v{}) has unreadable conditions; calls that reach it fail closed (FR3): {}",
                    rule.getRuleName(), rule.getVersion(), ex.getMessage());
            return new CompiledPolicyRule(rule, List.of(), true);
        }
    }

    /**
     * Reads stored conditions. Blank means "no conditions"; anything that cannot be read, or a condition
     * without a field, operator or expected value, is an error rather than an empty list (FR3).
     */
    private List<PolicyCondition> readConditions(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<PolicyCondition> conditions = objectMapper.readValue(json, new TypeReference<>() {});
        if (conditions == null) {
            throw new IllegalArgumentException("conditions are null");
        }
        for (PolicyCondition condition : conditions) {
            if (condition == null || condition.field() == null || condition.field().isBlank()
                    || condition.operator() == null || condition.expectedValue() == null) {
                throw new IllegalArgumentException("incomplete condition " + condition);
            }
        }
        return conditions;
    }

    /** Lenient read for listing rules only; evaluation uses {@link #compile}. */
    private List<PolicyCondition> parseConditions(String json) {
        try {
            return readConditions(json);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String serializeConditions(List<PolicyCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(conditions);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize policy conditions", e);
        }
    }
}
