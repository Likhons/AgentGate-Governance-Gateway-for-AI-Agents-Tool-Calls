package com.cse444.agentgate.registry.service;

import com.cse444.agentgate.registry.dto.OpenApiImportRequest;
import com.cse444.agentgate.registry.dto.RegisterToolRequest;
import com.cse444.agentgate.registry.dto.ToolResponse;
import com.cse444.agentgate.shared.domain.ToolType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports REST endpoints from an OpenAPI 3.x specification as governed AgentGate tools (FR1, QA5).
 */
@Service
public class OpenApiToolImporterService {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ToolRegistryService toolRegistryService;
    private final ObjectMapper objectMapper;

    public OpenApiToolImporterService(ToolRegistryService toolRegistryService, ObjectMapper objectMapper) {
        this.toolRegistryService = toolRegistryService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<ToolResponse> importFromOpenApiSpec(OpenApiImportRequest request) {
        SwaggerParseResult parseResult = new OpenAPIV3Parser().readContents(request.openApiContent(), null, null);
        OpenAPI openAPI = parseResult.getOpenAPI();
        if (openAPI == null || openAPI.getPaths() == null || openAPI.getPaths().isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid or empty OpenAPI specification: " + parseResult.getMessages()
            );
        }

        String baseUrl = resolveBaseUrl(openAPI, request.baseUrlOverride());
        List<ToolResponse> importedTools = new ArrayList<>();

        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : pathItem.readOperationsMap().entrySet()) {
                String httpMethod = opEntry.getKey().name().toUpperCase();
                Operation operation = opEntry.getValue();

                String toolName = deriveToolName(httpMethod, path, operation);
                String description = operation.getSummary() != null
                        ? operation.getSummary()
                        : (operation.getDescription() != null ? operation.getDescription() : httpMethod + " " + path);
                String inputSchema = buildJsonSchemaForOperation(operation);
                boolean writeOp = WRITE_METHODS.contains(httpMethod);

                RegisterToolRequest registerRequest = new RegisterToolRequest(
                        toolName,
                        description,
                        inputSchema,
                        request.defaultRiskLevel(),
                        request.owningTeam(),
                        ToolType.REST,
                        writeOp,
                        baseUrl + path,
                        httpMethod,
                        request.defaultTimeoutMs(),
                        request.approvalWebhookUrl()
                );

                importedTools.add(toolRegistryService.registerOrUpdateTool(registerRequest));
            }
        }

        return importedTools;
    }

    private String resolveBaseUrl(OpenAPI openAPI, String baseUrlOverride) {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            return baseUrlOverride.replaceAll("/+$", "");
        }
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()
                && openAPI.getServers().getFirst().getUrl() != null) {
            return openAPI.getServers().getFirst().getUrl().replaceAll("/+$", "");
        }
        return "http://localhost:8081";
    }

    private String deriveToolName(String httpMethod, String path, Operation operation) {
        if (operation.getOperationId() != null && !operation.getOperationId().isBlank()) {
            return operation.getOperationId().replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        }
        String sanitizedPath = path.replaceAll("[{}]", "")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return (httpMethod.toLowerCase() + "_" + sanitizedPath);
    }

    @SuppressWarnings("rawtypes")
    private String buildJsonSchemaForOperation(Operation operation) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                ObjectNode propNode = properties.putObject(param.getName());
                String type = (param.getSchema() != null && param.getSchema().getType() != null)
                        ? param.getSchema().getType()
                        : "string";
                propNode.put("type", type);
                if (param.getDescription() != null) {
                    propNode.put("description", param.getDescription());
                }
                if (Boolean.TRUE.equals(param.getRequired())) {
                    required.add(param.getName());
                }
            }
        }

        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
            MediaType jsonMedia = operation.getRequestBody().getContent().get("application/json");
            if (jsonMedia != null && jsonMedia.getSchema() != null) {
                Schema<?> bodySchema = jsonMedia.getSchema();
                if (bodySchema.getProperties() != null) {
                    for (Map.Entry<String, Schema> entry : bodySchema.getProperties().entrySet()) {
                        ObjectNode propNode = properties.putObject(entry.getKey());
                        String type = entry.getValue().getType() != null ? entry.getValue().getType() : "string";
                        propNode.put("type", type);
                    }
                }
                if (bodySchema.getRequired() != null) {
                    bodySchema.getRequired().forEach(required::add);
                }
            }
        }

        return root.toString();
    }
}
