package com.cse444.agentgate;

import com.cse444.agentgate.approval.domain.ApprovalRequest;
import com.cse444.agentgate.guard.chain.JsonSchemaValidationGuard;
import com.cse444.agentgate.guard.chain.PayloadTruncationOutputGuard;
import com.cse444.agentgate.guard.chain.PiiRedactionOutputGuard;
import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.domain.*;
import com.cse444.agentgate.shared.error.GuardRejectedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast Unit Tests for FR5 Guards (PII Redaction, Truncation, JSON Schema) and FR4 Approval State Pattern.
 */
class GuardAndStatePatternUnitTest {

    @Test
    @DisplayName("FR5 PII Redaction Guard: Redacts Emails, Bangladeshi Phone Numbers (+8801/01), and 10/13/17-digit NIDs by clearance")
    void piiRedactionByClearanceLevel() {
        PiiRedactionOutputGuard guard = new PiiRedactionOutputGuard();
        String rawToolOutput = """
                {"customer":"Rahim","email":"rahim.bd@example.com","phone":"+8801712-345678","altPhone":"01819876543","nid10":"1998765432","nid13":"1990123456789","nid17":"19901234567890123"}
                """;

        // 1. Clearance NONE -> Redacts Email, BD Phone, and BD NID
        String noneRedacted = guard.redactByClearance(rawToolOutput, ClearanceLevel.NONE);
        assertThat(noneRedacted)
                .contains("[REDACTED_EMAIL]")
                .contains("[REDACTED_BD_PHONE]")
                .contains("[REDACTED_BD_NID]")
                .doesNotContain("rahim.bd@example.com")
                .doesNotContain("01712-345678")
                .doesNotContain("01819876543")
                .doesNotContain("1998765432")
                .doesNotContain("1990123456789");

        // 2. Clearance INTERNAL -> Preserves Email, Redacts BD Phone and BD NID
        String internalRedacted = guard.redactByClearance(rawToolOutput, ClearanceLevel.INTERNAL);
        assertThat(internalRedacted)
                .contains("rahim.bd@example.com")
                .contains("[REDACTED_BD_PHONE]")
                .contains("[REDACTED_BD_NID]");

        // 3. Clearance FULL_PII -> Preserves all fields
        String fullCleared = guard.redactByClearance(rawToolOutput, ClearanceLevel.FULL_PII);
        assertThat(fullCleared).isEqualTo(rawToolOutput);
    }

    @Test
    @DisplayName("FR5 Payload Truncation Guard: Cuts oversized output and appends truncation notice")
    void payloadTruncationAppendsNotice() {
        AgentGateProperties props = new AgentGateProperties(
                new AgentGateProperties.SecurityProperties(
                        "secret-key-at-least-32-bytes-long-12345",
                        Duration.ofHours(1),
                        "user",
                        "password",
                        "approver",
                        "approver-pass",
                        "auditor",
                        "auditor-pass"
                ),
                new AgentGateProperties.PolicyProperties(Duration.ofSeconds(10), Duration.ofMinutes(5), true),
                new AgentGateProperties.ApprovalProperties(Duration.ofMinutes(15), Duration.ofSeconds(3)),
                new AgentGateProperties.GuardProperties(32, "[TRUNCATED_NOTICE]"),
                new AgentGateProperties.LimitProperties(60, 5000, 20),
                new AgentGateProperties.AuditProperties("audit-secret")
        );
        PayloadTruncationOutputGuard guard = new PayloadTruncationOutputGuard(props);
        String oversized = "A".repeat(100);

        String result = guard.transformOutput(null, oversized);
        assertThat(result).endsWith("[TRUNCATED_NOTICE]");
        assertThat(result.length()).isLessThan(60);
    }

    @Test
    @DisplayName("FR5 JSON Schema Guard: Rejects missing required fields and undeclared smuggled fields")
    void jsonSchemaValidationRejectsInvalidAndUndeclaredFields() {
        JsonSchemaValidationGuard guard = new JsonSchemaValidationGuard(new ObjectMapper());
        RegisteredTool tool = new RegisteredTool(
                UUID.randomUUID(),
                "issue_refund",
                "Refund tool",
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"amount\":{\"type\":\"number\"}},\"required\":[\"orderId\",\"amount\"]}",
                RiskLevel.MEDIUM,
                "Billing",
                ToolType.REST,
                true,
                "http://localhost:8081/refunds",
                "POST",
                2000
        );
        AgentCallIdentity identity = new AgentCallIdentity("bot-1", "cust-1", ClearanceLevel.NONE);

        // Valid arguments pass
        guard.verifyInput(new GuardInspectionContext(identity, tool, Map.of("orderId", "O-1", "amount", 500)));

        // Undeclared smuggled field is rejected
        assertThatThrownBy(() -> guard.verifyInput(
                new GuardInspectionContext(identity, tool, Map.of("orderId", "O-1", "amount", 500, "isAdmin", true))
        )).isInstanceOf(GuardRejectedException.class);
    }

    @Test
    @DisplayName("FR4 State Pattern: Terminal approval states forbid subsequent state transitions")
    void approvalStatePatternPreventsMutatingTerminalState() {
        ApprovalRequest req = new ApprovalRequest(
                UUID.randomUUID(),
                "bot-1",
                "cust-1",
                "bulk_payout",
                true,
                "{\"amount\":10000}",
                "rule-1",
                1,
                null,
                Instant.now().plusSeconds(600)
        );

        req.reject("sec-reviewer", "Suspicious destination account");
        assertThat(req.getState()).isEqualTo(ApprovalStateType.REJECTED);

        // Attempting to approve an already REJECTED request throws IllegalStateException
        assertThatThrownBy(() -> req.approve("another-reviewer", "Try override"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal state REJECTED");
    }
}
