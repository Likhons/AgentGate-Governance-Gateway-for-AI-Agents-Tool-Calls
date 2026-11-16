package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.shared.error.GuardRejectedException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Input Guard #2 (@Order(20)): Anti-Smuggling & Prompt-Injection Security Guard (FR5, QA1).
 * Detects and blocks malicious argument smuggling (path traversal, parameter smuggling,
 * SQL/command injection payloads, and prompt-injection override directives) to guarantee
 * 100% blocking of the QA1 red-team suite while passing legitimate requests untouched.
 */
@Component
@Order(20)
public class ArgumentSmugglingSecurityGuard implements InputCallGuard {

    private static final List<Pattern> SMUGGLING_PATTERNS = List.of(
            Pattern.compile("(?i)(?:ignore\\s+previous\\s+instructions|system\\s+override|bypass\\s+policy)"),
            Pattern.compile("(?i)(?:\\.\\./|\\.\\.\\\\|%2e%2e%2f|%00)"),
            Pattern.compile("(?i)(?:;\\s*(?:DROP|DELETE|UPDATE|INSERT)\\s+|'\\s*OR\\s+'?1'?\\s*=\\s*'?1)"),
            Pattern.compile("(?i)(?:__proto__|constructor\\.prototype|\\$\\{jndi:)"),
            Pattern.compile("[\\r\\n]+(?:Host:|Authorization:|X-On-Behalf-Of-User:)")
    );

    @Override
    public String guardName() {
        return "ArgumentSmugglingSecurityGuard";
    }

    @Override
    public void verifyInput(GuardInspectionContext context) {
        Map<String, Object> arguments = context.arguments();
        if (arguments == null || arguments.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";

            inspectStringForSmuggling(key, key);
            inspectStringForSmuggling(key, value);
        }
    }

    private void inspectStringForSmuggling(String fieldName, String candidate) {
        for (Pattern pattern : SMUGGLING_PATTERNS) {
            if (pattern.matcher(candidate).find()) {
                throw new GuardRejectedException(
                        guardName(),
                        "Blocked malicious argument smuggling / injection payload in field '" + fieldName + "' (QA1)"
                );
            }
        }
    }
}
