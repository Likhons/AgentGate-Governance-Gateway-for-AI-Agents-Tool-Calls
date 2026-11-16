package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.shared.domain.ClearanceLevel;
import com.cse444.agentgate.shared.util.PiiSanitizer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Output Guard #1 (@Order(10)): Clearance-Aware PII Redaction Guard (FR5, FR7).
 * Redacts sensitive fields in tool results and logged arguments based on the agent's ClearanceLevel:
 * - E-mail addresses -> [REDACTED_EMAIL]
 * - Bangladeshi phone numbers (+8801[3-9]xxxxxxxx or 01[3-9]xxxxxxxx) -> [REDACTED_BD_PHONE]
 * - Bangladesh National ID numbers (10, 13, or 17 digits) -> [REDACTED_BD_NID]
 */
@Component
@Order(10)
public class PiiRedactionOutputGuard implements OutputResultGuard {

    @Override
    public String guardName() {
        return "PiiRedactionOutputGuard";
    }

    @Override
    public String transformOutput(GuardInspectionContext context, String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }
        ClearanceLevel clearance = context.identity() != null
                ? context.identity().clearanceLevel()
                : ClearanceLevel.NONE;
        return redactByClearance(rawOutput, clearance);
    }

    public String redactByClearance(String text, ClearanceLevel clearance) {
        return PiiSanitizer.redactByClearance(text, clearance);
    }
}
