package com.cse444.agentgate.shared.util;

import com.cse444.agentgate.shared.domain.ClearanceLevel;

import java.util.regex.Pattern;

/**
 * Shared clearance-aware PII redaction helper (FR5, FR7).
 * Redacts e-mail addresses, Bangladeshi mobile phone numbers (+8801/01),
 * and Bangladesh National ID numbers (10, 13, or 17 digits).
 */
public final class PiiSanitizer {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b");

    // Apply BD Phone redaction before NID redaction so 11-digit phones are not partially matched as 10-digit NIDs
    private static final Pattern BD_PHONE_PATTERN =
            Pattern.compile("(?<!\\d)(?:\\+?880[-\\s]?|0)1[3-9]\\d{2}[-\\s]?\\d{6}(?!\\d)");

    private static final Pattern BD_NID_PATTERN =
            Pattern.compile("(?<!\\d)(?:\\d{17}|\\d{13}|\\d{10})(?!\\d)");

    private PiiSanitizer() {
    }

    public static String redactByClearance(String text, ClearanceLevel clearance) {
        if (text == null || text.isEmpty() || clearance == ClearanceLevel.FULL_PII) {
            return text;
        }

        String redacted = BD_PHONE_PATTERN.matcher(text).replaceAll("[REDACTED_BD_PHONE]");
        redacted = BD_NID_PATTERN.matcher(redacted).replaceAll("[REDACTED_BD_NID]");

        if (clearance == ClearanceLevel.NONE) {
            redacted = EMAIL_PATTERN.matcher(redacted).replaceAll("[REDACTED_EMAIL]");
        }
        return redacted;
    }
}
