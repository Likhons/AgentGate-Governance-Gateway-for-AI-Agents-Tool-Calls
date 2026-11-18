package com.cse444.agentgate.guard.web;

import com.cse444.agentgate.guard.dto.LimitBreachAlertResponse;
import com.cse444.agentgate.guard.service.LimitBreachAlertService;
import com.cse444.agentgate.guard.domain.AgentToolLimitConfig;
import com.cse444.agentgate.guard.dto.UpsertLimitConfigRequest;
import com.cse444.agentgate.guard.service.GuardPipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for configuring Rate Limits, Daily Quotas, and Write Blast-Radius Caps (FR6).
 */
@RestController
@RequestMapping("/api/v1/limits")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Guards & Rate Limits (FR5, FR6)", description = "Configure per-agent and per-tool rate limits, daily quotas, and write blast-radius caps")
public class LimitConfigController {

    private final GuardPipelineService guardPipelineService;
    private final LimitBreachAlertService limitBreachAlertService;

    public LimitConfigController(GuardPipelineService guardPipelineService, LimitBreachAlertService limitBreachAlertService) {
        this.guardPipelineService = guardPipelineService;
        this.limitBreachAlertService = limitBreachAlertService;
    }

    @PutMapping
    @Operation(summary = "Configure rate limits, daily quotas, and hourly write blast-radius cap for an agent and tool (FR6)")
    public AgentToolLimitConfig upsertLimit(@Valid @RequestBody UpsertLimitConfigRequest request) {
        return guardPipelineService.upsertLimitConfig(request);
    }

    @GetMapping
    @Operation(summary = "Paginated list of configured rate limits and blast-radius caps (C3, FR6)")
    public Page<AgentToolLimitConfig> listLimits(@PageableDefault(size = 20) Pageable pageable) {
        return guardPipelineService.listLimitConfigs(pageable);
    }

    @GetMapping("/alerts")
    @Operation(summary = "Paginated list of raised limit-breach alerts, newest first (C3, FR6)")
    public Page<LimitBreachAlertResponse> listAlerts(@PageableDefault(size = 20) Pageable pageable) {
        return limitBreachAlertService.listAlerts(pageable);
    }
}
