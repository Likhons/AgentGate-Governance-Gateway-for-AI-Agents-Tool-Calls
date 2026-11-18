package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.domain.AgentToolLimitConfig;
import com.cse444.agentgate.guard.domain.AgentToolLimitRepository;
import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.error.LimitExceededException;
import com.cse444.agentgate.shared.event.LimitBreachAlertEvent;
import com.cse444.agentgate.shared.event.LimitBreachAlertEvent.BreachType;
import com.cse444.agentgate.shared.util.FailSafeCaches;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Input Guard #3 (@Order(30)): Rate Limits, Daily Quotas & Write Blast-Radius Caps (FR6).
 * Enforces:
 * 1. Per-agent & per-tool per-minute rate limit (Bucket4j token bucket + Redis counter)
 * 2. Per-agent & per-tool daily quota (24-hour window)
 * 3. Per-agent hourly blast-radius cap on write calls (e.g., max 20 write calls/hour per agent)
 * On breach, blocks further calls, increments Micrometer metrics, and publishes LimitBreachAlertEvent.
 */
@Component
@Order(30)
public class RateLimitAndBlastRadiusGuard implements InputCallGuard {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAndBlastRadiusGuard.class);

    private record EffectiveLimits(int requestsPerMinute, int dailyQuota, int writeBlastRadiusPerHour) {}

    private record AgentToolKey(String agentId, String toolName) {}

    private final AgentToolLimitRepository limitRepository;
    private final AgentGateProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    private final Map<String, Bucket> minuteBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> dailyQuotaBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> writeBlastRadiusBuckets = new ConcurrentHashMap<>();
    /** Last-known-good limit overrides (QA4): served from cache while PostgreSQL is unreachable. */
    private final LoadingCache<AgentToolKey, EffectiveLimits> limitCache;
    private final LoadingCache<AgentToolKey, Integer> agentWriteCapCache;

    public RateLimitAndBlastRadiusGuard(
            AgentToolLimitRepository limitRepository,
            AgentGateProperties properties,
            StringRedisTemplate redisTemplate,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry
    ) {
        this.limitRepository = limitRepository;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        Duration refreshAfter = properties.policy().refreshInterval();
        Duration maxStaleness = properties.policy().lowRiskFailsafeTtl();
        this.limitCache = FailSafeCaches.lastKnownGood(5000, refreshAfter, maxStaleness, this::loadLimits);
        this.agentWriteCapCache = FailSafeCaches.lastKnownGood(2000, refreshAfter, maxStaleness, this::loadWriteCap);
    }

    @Override
    public String guardName() {
        return "RateLimitAndBlastRadiusGuard";
    }

    @Override
    public void verifyInput(GuardInspectionContext context) {
        String agentId = context.identity().agentClientId();
        String userId = context.identity().delegatedUserId();
        String toolName = context.tool().getName();
        boolean isWriteCall = context.tool().isWriteOperation();

        EffectiveLimits config = resolveLimits(agentId, toolName);
        String agentToolKey = agentId + ":" + toolName;

        // 1. Check per-minute rate limit (per agent + per tool)
        Bucket minuteBucket = minuteBuckets.computeIfAbsent(
                agentToolKey + ":" + config.requestsPerMinute(),
                k -> Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(config.requestsPerMinute())
                                .refillGreedy(config.requestsPerMinute(), Duration.ofMinutes(1))
                                .build())
                        .build()
        );
        if (!minuteBucket.tryConsume(1) || !tryIncrementRedisWindow("rl:min:" + agentToolKey, config.requestsPerMinute(), Duration.ofMinutes(1))) {
            raiseBreachAndThrow(agentId, userId, toolName, BreachType.PER_MINUTE_RATE_LIMIT, config.requestsPerMinute());
        }

        // 2. Check daily quota (per agent + per tool)
        Bucket dailyBucket = dailyQuotaBuckets.computeIfAbsent(
                agentToolKey + ":" + config.dailyQuota(),
                k -> Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(config.dailyQuota())
                                .refillGreedy(config.dailyQuota(), Duration.ofDays(1))
                                .build())
                        .build()
        );
        if (!dailyBucket.tryConsume(1) || !tryIncrementRedisWindow("rl:day:" + agentToolKey, config.dailyQuota(), Duration.ofDays(1))) {
            raiseBreachAndThrow(agentId, userId, toolName, BreachType.DAILY_QUOTA, config.dailyQuota());
        }

        // 3. Check write-call blast-radius cap per hour per agent (FR6)
        if (isWriteCall) {
            int blastCap = resolveAgentWriteBlastCap(agentId, toolName);
            Bucket writeBucket = writeBlastRadiusBuckets.computeIfAbsent(
                    agentId + ":writes:" + blastCap,
                    k -> Bucket.builder()
                            .addLimit(Bandwidth.builder()
                                    .capacity(blastCap)
                                    .refillGreedy(blastCap, Duration.ofHours(1))
                                    .build())
                            .build()
            );
            if (!writeBucket.tryConsume(1) || !tryIncrementRedisWindow("rl:write:hr:" + agentId, blastCap, Duration.ofHours(1))) {
                raiseBreachAndThrow(agentId, userId, toolName, BreachType.WRITE_BLAST_RADIUS_CAP, blastCap);
            }
        }
    }

    public void resetInMemoryBuckets() {
        minuteBuckets.clear();
        dailyQuotaBuckets.clear();
        writeBlastRadiusBuckets.clear();
        limitCache.invalidateAll();
        agentWriteCapCache.invalidateAll();
    }

    private int resolveAgentWriteBlastCap(String agentId, String toolName) {
        return agentWriteCapCache.get(new AgentToolKey(agentId, toolName));
    }

    private EffectiveLimits resolveLimits(String agentId, String toolName) {
        return limitCache.get(new AgentToolKey(agentId, toolName));
    }

    private Integer loadWriteCap(AgentToolKey key) {
        return limitRepository.findByAgentClientIdAndToolName(key.agentId(), key.toolName())
                .map(AgentToolLimitConfig::getWriteBlastRadiusPerHour)
                .orElseGet(() -> limitRepository.findAllByAgentClientId(key.agentId()).stream()
                        .mapToInt(AgentToolLimitConfig::getWriteBlastRadiusPerHour)
                        .max()
                        .orElse(properties.limits().defaultWriteBlastRadiusPerHour()));
    }

    private EffectiveLimits loadLimits(AgentToolKey key) {
        return limitRepository.findByAgentClientIdAndToolName(key.agentId(), key.toolName())
                .map(cfg -> new EffectiveLimits(
                        cfg.getRequestsPerMinute(),
                        cfg.getDailyQuota(),
                        cfg.getWriteBlastRadiusPerHour()
                ))
                .orElseGet(() -> new EffectiveLimits(
                        properties.limits().defaultRequestsPerMinute(),
                        properties.limits().defaultDailyQuota(),
                        properties.limits().defaultWriteBlastRadiusPerHour()
                ));
    }

    private boolean tryIncrementRedisWindow(String redisKey, int maxAllowed, Duration ttl) {
        try {
            Long current = redisTemplate.opsForValue().increment(redisKey);
            if (current != null && current == 1L) {
                redisTemplate.expire(redisKey, ttl);
            }
            return current == null || current <= maxAllowed;
        } catch (Exception ex) {
            // If Redis is unreachable, rely on local Bucket4j token bucket already evaluated above
            return true;
        }
    }

    private void raiseBreachAndThrow(
            String agentId,
            String userId,
            String toolName,
            BreachType breachType,
            int configuredLimit
    ) {
        String message = String.format(
                "Agent '%s' breached %s (limit=%d) on tool '%s' (FR6)",
                agentId, breachType, configuredLimit, toolName
        );
        log.warn("[LIMIT BREACH ALERT] {}", message);
        meterRegistry.counter("agentgate.limits.breaches", "agent", agentId, "tool", toolName, "type", breachType.name()).increment();

        eventPublisher.publishEvent(new LimitBreachAlertEvent(
                agentId,
                userId,
                toolName,
                breachType,
                configuredLimit,
                message,
                Instant.now()
        ));
        throw new LimitExceededException(breachType, configuredLimit, message);
    }
}
