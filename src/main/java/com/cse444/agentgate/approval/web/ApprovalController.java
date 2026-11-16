package com.cse444.agentgate.approval.web;

import com.cse444.agentgate.approval.dto.ApprovalDecisionRequest;
import com.cse444.agentgate.approval.dto.ApprovalRequestResponse;
import com.cse444.agentgate.approval.service.ApprovalWorkflowService;
import com.cse444.agentgate.shared.domain.ApprovalStateType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

/**
 * REST API for Human-in-the-Loop Approval Decisions on High-Risk Tool Calls (FR4).
 */
@RestController
@RequestMapping("/api/v1/approvals")
@PreAuthorize("hasAnyRole('ADMIN', 'APPROVER')")
@Tag(name = "Human Approval Workflow (FR4)", description = "Approve, edit arguments & approve, or reject paused high-risk tool calls")
public class ApprovalController {

    private final ApprovalWorkflowService approvalWorkflowService;

    public ApprovalController(ApprovalWorkflowService approvalWorkflowService) {
        this.approvalWorkflowService = approvalWorkflowService;
    }

    @GetMapping
    @Operation(summary = "Paginated list of approval requests, optionally filtered by state (C3, FR4)")
    public Page<ApprovalRequestResponse> listApprovals(
            @RequestParam(required = false) ApprovalStateType state,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return approvalWorkflowService.listApprovals(state, pageable);
    }

    @GetMapping("/{approvalId}")
    @Operation(summary = "Check status and result of a specific approval request (FR4)")
    public ApprovalRequestResponse getApproval(@PathVariable UUID approvalId) {
        return approvalWorkflowService.getApprovalStatus(approvalId);
    }

    @PostMapping("/{approvalId}/approve")
    @Operation(summary = "Approve a pending high-risk tool call, optionally editing its arguments first (FR4)")
    public ApprovalRequestResponse approve(
            @PathVariable UUID approvalId,
            @RequestBody(required = false) ApprovalDecisionRequest decision,
            Principal principal
    ) {
        String approver = principal != null ? principal.getName() : "human-approver";
        return approvalWorkflowService.approveRequest(approvalId, decision, approver);
    }

    @PostMapping("/{approvalId}/reject")
    @Operation(summary = "Reject a pending high-risk tool call (FR4)")
    public ApprovalRequestResponse reject(
            @PathVariable UUID approvalId,
            @RequestBody(required = false) ApprovalDecisionRequest decision,
            Principal principal
    ) {
        String approver = principal != null ? principal.getName() : "human-approver";
        String reason = decision != null ? decision.note() : "Rejected by human reviewer";
        return approvalWorkflowService.rejectRequest(approvalId, reason, approver);
    }
}
