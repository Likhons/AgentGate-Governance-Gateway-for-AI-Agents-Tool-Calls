package com.cse444.agentgate.identity.web;

import com.cse444.agentgate.identity.dto.AgentClientResponse;
import com.cse444.agentgate.identity.dto.RegisterAgentRequest;
import com.cse444.agentgate.identity.service.AgentIdentityService;
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

/**
 * REST Controller for AI Agent registration (FR2) and the Agent Kill Switch (FR8).
 * Agents obtain OAuth2 client_credentials tokens from Spring Authorization Server at {@code /oauth2/token}
 * (identity.security.AuthorizationServerConfig).
 */
@RestController
@Tag(name = "Agent Identity & OAuth2 (FR2, FR8)", description = "Agent registration as OAuth2 clients and the agent kill switch (tokens: POST /oauth2/token)")
public class AgentIdentityController {

    private final AgentIdentityService agentIdentityService;

    public AgentIdentityController(AgentIdentityService agentIdentityService) {
        this.agentIdentityService = agentIdentityService;
    }

    @PostMapping("/api/v1/agents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a new AI Agent as an OAuth2 client with clearance level (FR2, FR5)")
    public AgentClientResponse registerAgent(@Valid @RequestBody RegisterAgentRequest request) {
        return agentIdentityService.registerAgent(request);
    }

    @GetMapping("/api/v1/agents")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Paginated list of all registered AI Agent OAuth2 clients (C3, FR2)")
    public Page<AgentClientResponse> listAgents(@PageableDefault(size = 20, sort = "clientId") Pageable pageable) {
        return agentIdentityService.listAgents(pageable);
    }

    @PostMapping("/api/v1/agents/{clientId}/kill-switch")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Instantly disable an agent and cancel its pending approvals (FR8)")
    public AgentClientResponse disableAgent(
            @PathVariable String clientId,
            @RequestParam(defaultValue = "Administrative agent emergency kill switch") String reason,
            Principal principal
    ) {
        String actor = principal != null ? principal.getName() : "admin";
        return agentIdentityService.disableAgent(clientId, reason, actor);
    }

    @PostMapping("/api/v1/agents/{clientId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Re-enable a previously disabled agent (FR8)")
    public AgentClientResponse enableAgent(@PathVariable String clientId) {
        return agentIdentityService.enableAgent(clientId);
    }
}
