package com.cse444.agentgate.registry.service;

import com.cse444.agentgate.registry.adapter.DownstreamToolAdapter.DownstreamExecutionResult;
import com.cse444.agentgate.registry.adapter.ResilientDownstreamInvoker;
import com.cse444.agentgate.registry.domain.GlobalKillSwitchRepository;
import com.cse444.agentgate.registry.domain.GlobalKillSwitchState;
import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.registry.domain.RegisteredToolRepository;
import com.cse444.agentgate.registry.dto.RegisterToolRequest;
import com.cse444.agentgate.registry.dto.ToolResponse;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.domain.ToolStatus;
import com.cse444.agentgate.shared.event.KillSwitchActivatedEvent;
import com.cse444.agentgate.shared.util.FailSafeCaches;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core service managing Tool Registration (FR1), Kill Switches (FR8),
 * and Downstream Health Discovery Filtering (FR10).
 */
@Service
public class ToolRegistryService {

    private final RegisteredToolRepository toolRepository;
    private final GlobalKillSwitchRepository killSwitchRepository;
    private final ResilientDownstreamInvoker downstreamInvoker;
    private final ApplicationEventPublisher eventPublisher;

    // In-memory fast flag synced with DB for sub-millisecond write-kill-switch checks (QA2)
    private final AtomicBoolean allWriteToolsDisabled = new AtomicBoolean(false);
    /**
     * Last-known-good tool metadata (QA4): refreshed in the background and served from cache while
     * PostgreSQL is unreachable, for at most the low-risk fail-safe TTL. Kill switches and
     * re-registration on this instance invalidate the entry immediately (FR8).
     */
    private final LoadingCache<String, RegisteredTool> toolCache;

    public ToolRegistryService(
            RegisteredToolRepository toolRepository,
            GlobalKillSwitchRepository killSwitchRepository,
            ResilientDownstreamInvoker downstreamInvoker,
            ApplicationEventPublisher eventPublisher,
            AgentGateProperties properties
    ) {
        this.toolRepository = toolRepository;
        this.killSwitchRepository = killSwitchRepository;
        this.downstreamInvoker = downstreamInvoker;
        this.eventPublisher = eventPublisher;
        this.toolCache = FailSafeCaches.lastKnownGood(
                2000,
                properties.policy().refreshInterval(),
                properties.policy().lowRiskFailsafeTtl(),
                name -> toolRepository.findByName(name).orElse(null)
        );
    }

    @PostConstruct
    public void syncGlobalKillSwitchOnStartup() {
        boolean active = killSwitchRepository
                .findById(GlobalKillSwitchState.ALL_WRITE_TOOLS_KEY)
                .map(GlobalKillSwitchState::isActive)
                .orElse(false);
        allWriteToolsDisabled.set(active);
    }

    @Transactional
    public ToolResponse registerOrUpdateTool(RegisterToolRequest request) {
        RegisteredTool tool = toolRepository.findByName(request.name())
                .map(existing -> {
                    existing.updateMetadata(
                            request.description(),
                            request.inputSchema(),
                            request.riskLevel(),
                            request.owningTeam(),
                            request.writeOperation(),
                            request.downstreamUrl(),
                            request.httpMethod(),
                            request.timeoutMs()
                    );
                    return existing;
                })
                .orElseGet(() -> new RegisteredTool(
                        UUID.randomUUID(),
                        request.name(),
                        request.description(),
                        request.inputSchema(),
                        request.riskLevel(),
                        request.owningTeam(),
                        request.toolType(),
                        request.writeOperation(),
                        request.downstreamUrl(),
                        request.httpMethod(),
                        request.timeoutMs()
                ));
        tool.changeApprovalWebhookUrl(request.approvalWebhookUrl());

        RegisteredTool saved = toolRepository.save(tool);
        toolCache.invalidate(saved.getName());
        return toToolResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<ToolResponse> listAllTools(Pageable pageable) {
        return toolRepository.findAll(pageable).map(this::toToolResponse);
    }

    /**
     * Returns only tools that are:
     * 1. Administratively ENABLED (FR8),
     * 2. Not blocked by the global write-tools kill switch (FR8), and
     * 3. Have a healthy downstream CircuitBreaker (not OPEN / FORCED_OPEN) (FR10).
     */
    @Transactional(readOnly = true)
    public List<ToolResponse> listAvailableToolsForAgents() {
        boolean writeKilled = isAllWriteToolsKilled();
        return toolRepository.findAllByStatusOrderByNameAsc(ToolStatus.ENABLED).stream()
                .filter(tool -> !(writeKilled && tool.isWriteOperation()))
                .filter(tool -> downstreamInvoker.isToolHealthy(tool.getName()))
                .map(this::toToolResponse)
                .toList();
    }

    /**
     * Not transactional on purpose: the cached path must not need a database connection (QA4).
     */
    public RegisteredTool getRequiredToolByName(String toolName) {
        RegisteredTool tool = toolCache.get(toolName);
        if (tool == null) {
            throw new EntityNotFoundException("Tool not found: " + toolName);
        }
        return tool;
    }

    /**
     * FR8 Kill Switch: Instantly disables a single tool and publishes KillSwitchActivatedEvent
     * so pending approvals for this tool are immediately cancelled.
     */
    @Transactional
    public ToolResponse disableTool(String toolName, String reason, String adminActor) {
        RegisteredTool tool = toolRepository.findByName(toolName)
                .orElseThrow(() -> new EntityNotFoundException("Tool not found: " + toolName));
        tool.disable();
        RegisteredTool saved = toolRepository.save(tool);
        toolCache.invalidate(toolName);

        eventPublisher.publishEvent(new KillSwitchActivatedEvent(
                KillSwitchActivatedEvent.Scope.TOOL,
                toolName,
                reason,
                adminActor,
                Instant.now()
        ));
        return toToolResponse(saved);
    }

    @Transactional
    public ToolResponse enableTool(String toolName) {
        RegisteredTool tool = toolRepository.findByName(toolName)
                .orElseThrow(() -> new EntityNotFoundException("Tool not found: " + toolName));
        tool.enable();
        RegisteredTool saved = toolRepository.save(tool);
        toolCache.invalidate(toolName);
        return toToolResponse(saved);
    }

    /**
     * FR8 Global Write Kill Switch: Instantly disables or re-enables all write tools across AgentGate.
     */
    @Transactional
    public void setAllWriteToolsKillSwitch(boolean disableAllWrites, String reason, String adminActor) {
        GlobalKillSwitchState state = killSwitchRepository
                .findById(GlobalKillSwitchState.ALL_WRITE_TOOLS_KEY)
                .orElseGet(() -> new GlobalKillSwitchState(
                        GlobalKillSwitchState.ALL_WRITE_TOOLS_KEY,
                        disableAllWrites,
                        reason,
                        adminActor
                ));
        state.update(disableAllWrites, reason, adminActor);
        killSwitchRepository.save(state);
        allWriteToolsDisabled.set(disableAllWrites);

        if (disableAllWrites) {
            eventPublisher.publishEvent(new KillSwitchActivatedEvent(
                    KillSwitchActivatedEvent.Scope.ALL_WRITE_TOOLS,
                    "*",
                    reason,
                    adminActor,
                    Instant.now()
            ));
        }
    }

    public boolean isAllWriteToolsKilled() {
        return allWriteToolsDisabled.get();
    }

    /**
     * Invokes a registered tool after verifying it is enabled, not write-killed, and healthy (FR8, FR10).
     */
    public DownstreamExecutionResult executeDownstreamTool(RegisteredTool tool, Map<String, Object> arguments) {
        if (!tool.isEnabled()) {
            throw new IllegalStateException("Tool '" + tool.getName() + "' is disabled by Kill Switch (FR8)");
        }
        if (tool.isWriteOperation() && isAllWriteToolsKilled()) {
            throw new IllegalStateException("All write tools are currently disabled by Global Kill Switch (FR8)");
        }
        if (!downstreamInvoker.isToolHealthy(tool.getName())) {
            throw new IllegalStateException("Downstream circuit breaker for tool '" + tool.getName() + "' is OPEN (FR10)");
        }
        return downstreamInvoker.invokeWithResilience(tool, arguments);
    }

    private ToolResponse toToolResponse(RegisteredTool tool) {
        boolean healthy = downstreamInvoker.isToolHealthy(tool.getName());
        String circuitState = downstreamInvoker.getCircuitState(tool.getName());
        return ToolResponse.from(tool, healthy, circuitState);
    }
}
