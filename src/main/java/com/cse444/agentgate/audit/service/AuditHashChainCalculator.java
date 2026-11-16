package com.cse444.agentgate.audit.service;

import com.cse444.agentgate.shared.config.AgentGateProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Computes deterministic HMAC-SHA256 hash links for the Tamper-Evident Audit Chain (FR7, QA3).
 * Any modification to agent, user, tool, arguments, policy rule, approval, outcome, latency,
 * timestamp, or previous_hash alters the computed 64-char hex digest.
 */
@Component
public class AuditHashChainCalculator {

    private final byte[] hmacSecretBytes;

    public AuditHashChainCalculator(AgentGateProperties properties) {
        this.hmacSecretBytes = properties.audit().hmacSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String computeEntryHash(
            long sequenceNumber,
            UUID id,
            String traceId,
            String agentClientId,
            String delegatedUserId,
            String toolName,
            String redactedArgumentsJson,
            String policyRuleName,
            int policyRuleVersion,
            UUID approvalId,
            String approvalState,
            String outcome,
            long latencyMs,
            Instant occurredAt,
            String previousHash
    ) {
        String canonicalPayload = String.join("|",
                String.valueOf(sequenceNumber),
                String.valueOf(id),
                nullSafe(traceId),
                nullSafe(agentClientId),
                nullSafe(delegatedUserId),
                nullSafe(toolName),
                nullSafe(redactedArgumentsJson),
                nullSafe(policyRuleName),
                String.valueOf(policyRuleVersion),
                nullSafe(approvalId != null ? approvalId.toString() : null),
                nullSafe(approvalState),
                nullSafe(outcome),
                String.valueOf(latencyMs),
                String.valueOf(occurredAt.toEpochMilli()),
                nullSafe(previousHash)
        );

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecretBytes, "HmacSHA256"));
            byte[] digest = mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 audit hash chain", ex);
        }
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
