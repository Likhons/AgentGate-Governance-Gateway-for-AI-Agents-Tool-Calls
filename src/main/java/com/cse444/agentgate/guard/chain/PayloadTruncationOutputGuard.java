package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Output Guard #2 (@Order(20)): Oversized Payload Truncation Guard (FR5).
 * Cuts tool results exceeding the configured byte ceiling and appends a clear notice.
 */
@Component
@Order(20)
public class PayloadTruncationOutputGuard implements OutputResultGuard {

    private final AgentGateProperties properties;

    public PayloadTruncationOutputGuard(AgentGateProperties properties) {
        this.properties = properties;
    }

    @Override
    public String guardName() {
        return "PayloadTruncationOutputGuard";
    }

    @Override
    public String transformOutput(GuardInspectionContext context, String rawOutput) {
        if (rawOutput == null) {
            return "";
        }
        int maxBytes = properties.guards().maxResponseBytes();
        byte[] utf8Bytes = rawOutput.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length <= maxBytes) {
            return rawOutput;
        }
        String cut = new String(utf8Bytes, 0, maxBytes, StandardCharsets.UTF_8);
        return cut + "\n" + properties.guards().truncationNotice();
    }
}
