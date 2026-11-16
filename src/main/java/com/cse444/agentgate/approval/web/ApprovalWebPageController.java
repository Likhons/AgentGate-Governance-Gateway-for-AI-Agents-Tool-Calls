package com.cse444.agentgate.approval.web;

import com.cse444.agentgate.approval.service.ApprovalWorkflowService;
import com.cse444.agentgate.shared.domain.ApprovalStateType;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered MVC Web Console for Human Approvers to review and decide on
 * pending high-risk tool calls (FR4).
 */
@Controller
@RequestMapping("/approvals/console")
@PreAuthorize("hasAnyRole('ADMIN', 'APPROVER')")
public class ApprovalWebPageController {

    private final ApprovalWorkflowService approvalWorkflowService;

    public ApprovalWebPageController(ApprovalWorkflowService approvalWorkflowService) {
        this.approvalWorkflowService = approvalWorkflowService;
    }

    @GetMapping
    public String renderApprovalConsole(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model
    ) {
        model.addAttribute("pendingApprovals",
                approvalWorkflowService.listApprovals(ApprovalStateType.PENDING, PageRequest.of(page, size)));
        model.addAttribute("recentApprovals",
                approvalWorkflowService.listApprovals(null, PageRequest.of(page, size)));
        return "approvals";
    }
}
