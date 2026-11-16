package com.cse444.agentgate.registry.adapter;

import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.shared.domain.ToolType;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * Design Pattern: Adapter Pattern (FR1).
 * Adapts plain downstream REST HTTP endpoints into AgentGate tools.
 * Supports path parameter interpolation (e.g. /orders/{orderId}), query parameters for GET/DELETE,
 * and JSON request bodies for POST/PUT/PATCH.
 */
@Component
public class RestDownstreamToolAdapter implements DownstreamToolAdapter {

    private final RestClient restClient;

    public RestDownstreamToolAdapter(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ToolType supportsType() {
        return ToolType.REST;
    }

    @Override
    public DownstreamExecutionResult invoke(RegisteredTool tool, Map<String, Object> arguments) {
        long startNanos = System.nanoTime();
        Map<String, Object> remainingArgs = new HashMap<>(arguments != null ? arguments : Map.of());

        // 1. Interpolate any {pathParam} placeholders in the downstream URL
        String resolvedUrl = tool.getDownstreamUrl();
        for (Map.Entry<String, Object> entry : Map.copyOf(remainingArgs).entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (resolvedUrl.contains(placeholder) && entry.getValue() != null) {
                resolvedUrl = resolvedUrl.replace(placeholder, String.valueOf(entry.getValue()));
                remainingArgs.remove(entry.getKey());
            }
        }

        HttpMethod method = HttpMethod.valueOf(tool.getHttpMethod().toUpperCase());

        // 2. For GET/DELETE, attach remaining arguments as query parameters
        if ((method == HttpMethod.GET || method == HttpMethod.DELETE) && !remainingArgs.isEmpty()) {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(resolvedUrl);
            remainingArgs.forEach((k, v) -> {
                if (v != null) {
                    uriBuilder.queryParam(k, v);
                }
            });
            resolvedUrl = uriBuilder.build().toUriString();
        }

        RestClient.RequestBodySpec requestSpec = restClient.method(method)
                .uri(resolvedUrl)
                .accept(MediaType.APPLICATION_JSON);

        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            requestSpec.contentType(MediaType.APPLICATION_JSON).body(remainingArgs);
        }

        ResponseEntity<String> response = requestSpec.retrieve().toEntity(String.class);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        return new DownstreamExecutionResult(
                response.getStatusCode().is2xxSuccessful(),
                response.getStatusCode().value(),
                response.getBody() != null ? response.getBody() : "{}",
                latencyMs
        );
    }
}
