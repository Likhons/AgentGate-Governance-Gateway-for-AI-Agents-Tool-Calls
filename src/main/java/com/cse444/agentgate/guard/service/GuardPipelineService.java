package com.cse444.agentgate.guard.service;

import com.cse444.agentgate.guard.chain.InputCallGuard;
import com.cse444.agentgate.guard.chain.OutputResultGuard;
import com.cse444.agentgate.guard.chain.PiiRedactionOutputGuard;
import com.cse444.agentgate.guard.chain.RateLimitAndBlastRadiusGuard;
import com.cse444.agentgate.guard.domain.AgentToolLimitConfig;
import com.cse444.agentgate.guard.domain.AgentToolLimitRepository;
import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.guard.dto.UpsertLimitConfigRequest;
import com.cse444.agentgate.shared.domain.ClearanceLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the ordered Chain of Responsibility for InputCallGuards and OutputResultGuards (FR5, FR6).
 * Because Spring automatically injects List<InputCallGuard> and List<OutputResultGuard> sorted by @Order,
 * adding a new guard is simply adding a new @Component bean without editing this class.
 */
@Service
public class GuardPipelineService {

    private final List<InputCallGuard> inputGuards;
    private final List<OutputResultGuard> outputGuards;
    private final PiiRedactionOutputGuard piiRedactionGuard;
    private final RateLimitAndBlastRadiusGuard rateLimitGuard;
    private final AgentToolLimitRepository limitRepository;
    private final ObjectMapper objectMapper;

    public GuardPipelineService(
            List<InputCallGuard> inputGuards,
            List<OutputResultGuard> outputGuards,
            PiiRedactionOutputGuard piiRedactionGuard,
            RateLimitAndBlastRadiusGuard rateLimitGuard,
            AgentToolLimitRepository limitRepository,
            ObjectMapper objectMapper
    ) {
        this.inputGuards = inputGuards;
        this.outputGuards = outputGuards;
        this.piiRedactionGuard = piiRedactionGuard;
        this.rateLimitGuard = rateLimitGuard;
        this.limitRepository = limitRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes all ordered InputCallGuard beans (Schema Validation -> Anti-Smuggling -> Rate Limits & Blast Radius).
     */
    public void runInputGuards(GuardInspectionContext context) {
        for (InputCallGuard guard : inputGuards) {
            guard.verifyInput(context);
        }
    }

    /**
     * Executes all ordered OutputResultGuard beans (Clearance PII Redaction -> Payload Truncation).
     */
    public String runOutputGuards(GuardInspectionContext context, String rawResult) {
        String current = rawResult;
        for (OutputResultGuard guard : outputGuards) {
            current = guard.transformOutput(context, current);
        }
        return current;
    }

    /**
     * Redacts sensitive PII from tool call arguments before persisting to the Audit Trail (FR5, FR7).
     */
    public String redactArgumentsForAudit(Map<String, Object> arguments) {
        try {
            String rawJson = objectMapper.writeValueAsString(arguments != null ? arguments : Map.of());
            return piiRedactionGuard.redactByClearance(rawJson, ClearanceLevel.NONE);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Transactional
    public AgentToolLimitConfig upsertLimitConfig(UpsertLimitConfigRequest request) {
        AgentToolLimitConfig config = limitRepository
                .findByAgentClientIdAndToolName(request.agentClientId(), request.toolName())
                .map(existing -> {
                    existing.updateLimits(
                            request.requestsPerMinute(),
                            request.dailyQuota(),
                            request.writeBlastRadiusPerHour()
                    );
                    return existing;
                })
                .orElseGet(() -> new AgentToolLimitConfig(
                        UUID.randomUUID(),
                        request.agentClientId(),
                        request.toolName(),
                        request.requestsPerMinute(),
                        request.dailyQuota(),
                        request.writeBlastRadiusPerHour()
                ));
        AgentToolLimitConfig saved = limitRepository.save(config);
        rateLimitGuard.resetInMemoryBuckets();
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<AgentToolLimitConfig> listLimitConfigs(Pageable pageable) {
        return limitRepository.findAll(pageable);
    }
}
