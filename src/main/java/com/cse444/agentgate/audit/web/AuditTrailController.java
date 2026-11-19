package com.cse444.agentgate.audit.web;

import com.cse444.agentgate.audit.dto.AuditChainVerificationReport;
import com.cse444.agentgate.audit.dto.AuditLogResponse;
import com.cse444.agentgate.audit.service.AuditTrailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST Controller for Searching, Exporting, and Verifying the Tamper-Evident Audit Trail (FR7, QA3).
 */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
@Tag(name = "Tamper-Evident Audit Trail (FR7, QA3)", description = "Search, export, and cryptographically verify hash-chained audit logs")
public class AuditTrailController {

    private final AuditTrailService auditTrailService;

    public AuditTrailController(AuditTrailService auditTrailService) {
        this.auditTrailService = auditTrailService;
    }

    @GetMapping
    @Operation(summary = "Search paginated hash-chained audit records by call ID, agent, user, tool, outcome, or rule (FR7, QA3)")
    public Page<AuditLogResponse> searchAuditLogs(
            @RequestParam(required = false) UUID callId,
            @RequestParam(required = false) String agentClientId,
            @RequestParam(required = false) String delegatedUserId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String policyRuleName,
            @PageableDefault(size = 20, sort = "sequenceNumber", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return auditTrailService.searchAuditLogs(
                callId, agentClientId, delegatedUserId, toolName, outcome, policyRuleName, pageable);
    }

    @GetMapping("/verify")
    @Operation(summary = "Verify cryptographic integrity of the entire SHA-256 hash chain (FR7, QA3)")
    public AuditChainVerificationReport verifyAuditChain() {
        return auditTrailService.verifyChainIntegrity();
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(summary = "Export audit trail records matching the same filters as the search as CSV (FR7)")
    public String exportCsv(
            @RequestParam(required = false) UUID callId,
            @RequestParam(required = false) String agentClientId,
            @RequestParam(required = false) String delegatedUserId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String policyRuleName
    ) {
        return auditTrailService.exportAuditLogsAsCsv(
                callId, agentClientId, delegatedUserId, toolName, outcome, policyRuleName);
    }
}
