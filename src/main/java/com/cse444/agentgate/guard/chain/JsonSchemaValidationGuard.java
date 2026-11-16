package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.shared.error.GuardRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Input Guard #1 (@Order(10)): JSON Schema Validation Guard (FR5, QA1, QA2).
 * Validates incoming tool call arguments against the tool's registered JSON Schema
 * and rejects undeclared/unexpected fields when properties are defined.
 * Caches compiled JsonSchema instances in Caffeine for sub-millisecond latency (QA2).
 */
@Component
@Order(10)
public class JsonSchemaValidationGuard implements InputCallGuard {

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;
    private final Cache<String, JsonSchema> compiledSchemaCache;

    public JsonSchemaValidationGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.compiledSchemaCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build();
    }

    @Override
    public String guardName() {
        return "JsonSchemaValidationGuard";
    }

    @Override
    public void verifyInput(GuardInspectionContext context) {
        String rawSchema = context.tool().getInputSchema();
        if (rawSchema == null || rawSchema.isBlank()) {
            return;
        }

        try {
            JsonNode schemaNode = objectMapper.readTree(rawSchema);
            Map<String, Object> args = context.arguments() != null ? context.arguments() : Map.of();
            JsonNode argsNode = objectMapper.valueToTree(args);

            // Reject undeclared extra arguments if schema specifies a fixed properties object (anti-smuggling QA1)
            JsonNode definedProps = schemaNode.get("properties");
            boolean additionalPropsAllowed = schemaNode.has("additionalProperties")
                    && schemaNode.get("additionalProperties").asBoolean(false);
            if (definedProps != null && definedProps.isObject() && !additionalPropsAllowed) {
                Iterator<String> fieldNames = argsNode.fieldNames();
                while (fieldNames.hasNext()) {
                    String argField = fieldNames.next();
                    if (!definedProps.has(argField)) {
                        throw new GuardRejectedException(
                                guardName(),
                                "Undeclared argument field '" + argField + "' is not permitted by tool schema for '"
                                        + context.tool().getName() + "'"
                        );
                    }
                }
            }

            JsonSchema schema = compiledSchemaCache.get(rawSchema, schemaFactory::getSchema);
            Set<ValidationMessage> errors = schema.validate(argsNode);
            if (!errors.isEmpty()) {
                String summary = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining("; "));
                throw new GuardRejectedException(
                        guardName(),
                        "Arguments failed JSON Schema validation for tool '" + context.tool().getName() + "': " + summary
                );
            }
        } catch (GuardRejectedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GuardRejectedException(
                    guardName(),
                    "Invalid JSON Schema or malformed arguments for tool '" + context.tool().getName() + "': " + ex.getMessage()
            );
        }
    }
}
