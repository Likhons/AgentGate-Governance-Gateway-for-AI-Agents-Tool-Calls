package com.cse444.agentgate.policy.web;

import com.cse444.agentgate.policy.dto.CreatePolicyRuleRequest;
import com.cse444.agentgate.policy.dto.PolicyEvaluationDecision;
import com.cse444.agentgate.policy.dto.PolicyPlaygroundRequest;
import com.cse444.agentgate.policy.dto.PolicyRuleResponse;
import com.cse444.agentgate.policy.service.PolicyEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * REST Controller for Versioned Policies (FR3), Hot-Reload (FR3), and Policy Playground (FR9).
 */
@RestController
@RequestMapping("/api/v1/policies")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Policies & Playground (FR3, FR9)", description = "Versioned allow/deny rules, 10s hot-reload, dry-run mode, and Policy Playground")
public class PolicyController {

    private final PolicyEngineService policyEngineService;

    public PolicyController(PolicyEngineService policyEngineService) {
        this.policyEngineService = policyEngineService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new policy rule or a new version of an existing rule (FR3)")
    public PolicyRuleResponse createOrUpdatePolicy(
            @Valid @RequestBody CreatePolicyRuleRequest request,
            Principal principal
    ) {
        String author = principal != null ? principal.getName() : "admin";
        return policyEngineService.createOrVersionPolicyRule(request, author);
    }

    @GetMapping
    @Operation(summary = "Paginated list of all versioned policy rules (C3, FR3)")
    public Page<PolicyRuleResponse> listPolicies(@PageableDefault(size = 20) Pageable pageable) {
        return policyEngineService.listPolicyRules(pageable);
    }

    @PostMapping("/reload")
    @Operation(summary = "Trigger an immediate hot-reload of the active policy snapshot (FR3)")
    public Map<String, Object> triggerHotReload() {
        policyEngineService.refreshPolicySnapshot();
        return Map.of("status", "RELOADED");
    }

    @PostMapping("/playground")
    @Operation(summary = "Test a sample tool call against current or draft policies and inspect the matching rule (FR9)")
    public PolicyEvaluationDecision evaluateInPlayground(@Valid @RequestBody PolicyPlaygroundRequest request) {
        return policyEngineService.simulateInPlayground(request);
    }
}
