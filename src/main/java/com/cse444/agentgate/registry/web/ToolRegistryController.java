package com.cse444.agentgate.registry.web;

import com.cse444.agentgate.registry.dto.OpenApiImportRequest;
import com.cse444.agentgate.registry.dto.RegisterToolRequest;
import com.cse444.agentgate.registry.dto.ToolResponse;
import com.cse444.agentgate.registry.service.OpenApiToolImporterService;
import com.cse444.agentgate.registry.service.ToolRegistryService;
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
import java.util.List;
import java.util.Map;

/**
 * Administrative REST API for Tool Registry (FR1), OpenAPI Import (FR1/QA5),
 * and Tool/Global Write Kill Switches (FR8).
 */
@RestController
@RequestMapping("/api/v1/tools")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Tool Registry & Kill Switch", description = "Register MCP/REST tools, import OpenAPI specs, and trigger kill switches")
public class ToolRegistryController {

    private final ToolRegistryService toolRegistryService;
    private final OpenApiToolImporterService openApiImporterService;

    public ToolRegistryController(
            ToolRegistryService toolRegistryService,
            OpenApiToolImporterService openApiImporterService
    ) {
        this.toolRegistryService = toolRegistryService;
        this.openApiImporterService = openApiImporterService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register or update a downstream MCP or REST tool (FR1)")
    public ToolResponse registerTool(@Valid @RequestBody RegisterToolRequest request) {
        return toolRegistryService.registerOrUpdateTool(request);
    }

    @PostMapping("/import-openapi")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import REST endpoints from an OpenAPI 3.x specification (FR1, QA5)")
    public List<ToolResponse> importOpenApi(@Valid @RequestBody OpenApiImportRequest request) {
        return openApiImporterService.importFromOpenApiSpec(request);
    }

    @GetMapping
    @Operation(summary = "Paginated list of all registered tools with circuit-breaker health status (C3, FR1, FR10)")
    public Page<ToolResponse> listTools(@PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return toolRegistryService.listAllTools(pageable);
    }

    @PostMapping("/{toolName}/kill-switch")
    @Operation(summary = "Instantly disable a specific tool and cancel its pending approvals (FR8)")
    public ToolResponse disableTool(
            @PathVariable String toolName,
            @RequestParam(defaultValue = "Administrative emergency kill switch") String reason,
            Principal principal
    ) {
        String actor = principal != null ? principal.getName() : "admin";
        return toolRegistryService.disableTool(toolName, reason, actor);
    }

    @PostMapping("/{toolName}/enable")
    @Operation(summary = "Re-enable a previously disabled tool (FR8)")
    public ToolResponse enableTool(@PathVariable String toolName) {
        return toolRegistryService.enableTool(toolName);
    }

    @PostMapping("/kill-switch/write-tools")
    @Operation(summary = "Instantly disable or re-enable ALL write tools across AgentGate (FR8)")
    public Map<String, Object> toggleAllWriteToolsKillSwitch(
            @RequestParam boolean disableAllWrites,
            @RequestParam(defaultValue = "Global write-tools emergency kill switch") String reason,
            Principal principal
    ) {
        String actor = principal != null ? principal.getName() : "admin";
        toolRegistryService.setAllWriteToolsKillSwitch(disableAllWrites, reason, actor);
        return Map.of(
                "allWriteToolsDisabled", disableAllWrites,
                "reason", reason,
                "updatedBy", actor
        );
    }
}
