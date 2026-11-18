package com.cse444.agentgate.registry.adapter;

import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.shared.domain.ToolType;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Design Pattern: Adapter Pattern (FR1).
 * Consumes downstream MCP servers with the MCP Java SDK client that ships with the Spring AI MCP client
 * starter: a real MCP session (the {@code initialize} handshake, then {@code tools/call}) over Streamable
 * HTTP, or over the legacy HTTP+SSE transport when the registered URL ends in {@code /sse}.
 * One initialized client is reused per downstream URL and discarded after a transport failure, so the
 * next call reconnects. Timeouts and circuit breaking come from {@link ResilientDownstreamInvoker} (FR10).
 * The registered tool name is the downstream MCP tool name.
 */
@Component
public class McpDownstreamToolAdapter implements DownstreamToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpDownstreamToolAdapter.class);
    private static final McpSchema.Implementation CLIENT_INFO = new McpSchema.Implementation("agentgate", "1.0.0");

    private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
    private final Map<String, McpSyncClient> clientsByUrl = new ConcurrentHashMap<>();
    private final ReentrantLock connectLock = new ReentrantLock();

    @Override
    public ToolType supportsType() {
        return ToolType.MCP;
    }

    @Override
    public DownstreamExecutionResult invoke(RegisteredTool tool, Map<String, Object> arguments) {
        long startNanos = System.nanoTime();
        String url = tool.getDownstreamUrl();
        McpSyncClient client = clientFor(url, Duration.ofMillis(tool.getTimeoutMs()));

        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(
                    tool.getName(), arguments != null ? arguments : Map.of()));
        } catch (RuntimeException ex) {
            discard(url, client);
            throw ex;
        }

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        // isError is a tool-level result (the server is healthy), so it is passed through, not thrown
        boolean toolReportedError = Boolean.TRUE.equals(result.isError());
        return new DownstreamExecutionResult(!toolReportedError, 200, toJson(result), latencyMs);
    }

    @PreDestroy
    void closeClients() {
        clientsByUrl.forEach((url, client) -> discard(url, client));
    }

    private McpSyncClient clientFor(String url, Duration timeout) {
        McpSyncClient existing = clientsByUrl.get(url);
        if (existing != null) {
            return existing;
        }
        // A lock rather than computeIfAbsent: the handshake is network I/O and must not pin virtual threads
        connectLock.lock();
        try {
            existing = clientsByUrl.get(url);
            if (existing != null) {
                return existing;
            }
            McpSyncClient client = McpClient.sync(transportFor(url, timeout))
                    .clientInfo(CLIENT_INFO)
                    .requestTimeout(timeout)
                    .initializationTimeout(timeout)
                    .build();
            try {
                client.initialize();
            } catch (RuntimeException ex) {
                client.close();
                throw ex;
            }
            clientsByUrl.put(url, client);
            log.info("Connected to downstream MCP server {} ({})", url, client.getServerInfo());
            return client;
        } finally {
            connectLock.unlock();
        }
    }

    private static McpClientTransport transportFor(String url, Duration timeout) {
        URI uri = URI.create(url);
        String baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/mcp" : uri.getRawPath();
        String endpoint = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        if (path.endsWith("/sse")) {
            return HttpClientSseClientTransport.builder(baseUrl)
                    .sseEndpoint(endpoint)
                    .connectTimeout(timeout)
                    .build();
        }
        return HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(endpoint)
                .connectTimeout(timeout)
                .openConnectionOnStartup(false)
                .build();
    }

    private void discard(String url, McpSyncClient client) {
        clientsByUrl.remove(url, client);
        try {
            client.close();
        } catch (RuntimeException ex) {
            log.debug("Ignoring error while closing MCP client for {}: {}", url, ex.getMessage());
        }
    }

    private String toJson(McpSchema.CallToolResult result) {
        try {
            return jsonMapper.writeValueAsString(result);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not serialise MCP tool result", ex);
        }
    }
}
