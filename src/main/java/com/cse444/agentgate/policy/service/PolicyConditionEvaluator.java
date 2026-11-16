package com.cse444.agentgate.policy.service;

import com.cse444.agentgate.policy.domain.PolicyCondition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Design Pattern: Strategy Pattern (FR3).
 * Evaluates structured argument conditions on a tool call, including numeric caps
 * (e.g., refund amount <= 2,000 BDT) and delegated-user ownership checks
 * (e.g., customerId == requesting customer's identity).
 */
@Component
public class PolicyConditionEvaluator {

    public boolean matchesAll(
            List<PolicyCondition> conditions,
            Map<String, Object> arguments,
            String delegatedUserId
    ) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        if (arguments == null) {
            return false;
        }
        for (PolicyCondition condition : conditions) {
            if (!matchesSingle(condition, arguments, delegatedUserId)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesSingle(
            PolicyCondition condition,
            Map<String, Object> arguments,
            String delegatedUserId
    ) {
        Object rawActual = arguments.get(condition.field());
        if (rawActual == null) {
            return false;
        }
        String actualStr = String.valueOf(rawActual).trim();
        String expectedStr = resolveExpectedValue(condition.expectedValue(), delegatedUserId);
        if (expectedStr == null) {
            return false;
        }

        return switch (condition.operator()) {
            case EQ -> actualStr.equals(expectedStr);
            case NEQ -> !actualStr.equals(expectedStr);
            case MATCHES_DELEGATED_USER -> delegatedUserId != null
                    && !delegatedUserId.isBlank()
                    && actualStr.equals(delegatedUserId.trim());
            case LTE -> compareNumeric(actualStr, expectedStr) <= 0;
            case GTE -> compareNumeric(actualStr, expectedStr) >= 0;
            case LT -> compareNumeric(actualStr, expectedStr) < 0;
            case GT -> compareNumeric(actualStr, expectedStr) > 0;
            case IN -> {
                Set<String> allowed = Arrays.stream(expectedStr.split(","))
                        .map(String::trim)
                        .collect(Collectors.toSet());
                yield allowed.contains(actualStr);
            }
            case REGEX -> Pattern.compile(expectedStr).matcher(actualStr).matches();
        };
    }

    private String resolveExpectedValue(String expected, String delegatedUserId) {
        if ("$user".equalsIgnoreCase(expected) || "${user}".equalsIgnoreCase(expected)) {
            return (delegatedUserId != null && !delegatedUserId.isBlank()) ? delegatedUserId.trim() : null;
        }
        return expected;
    }

    private int compareNumeric(String actualStr, String expectedStr) {
        try {
            BigDecimal actual = new BigDecimal(actualStr);
            BigDecimal expected = new BigDecimal(expectedStr);
            return actual.compareTo(expected);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Non-numeric argument value '" + actualStr + "' supplied for numeric policy condition"
            );
        }
    }
}
