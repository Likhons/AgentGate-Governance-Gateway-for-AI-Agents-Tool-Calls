package com.cse444.agentgate.gateway.service;

import com.cse444.agentgate.registry.dto.ToolResponse;
import com.cse444.agentgate.registry.service.ToolRegistryService;
import com.cse444.agentgate.shared.event.KillSwitchActivatedEvent;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps the live Spring AI MCP server tool list in step with the tool registry (FR1, FR8, FR10).
 * <p>
 * Spring AI reads {@code ToolCallbackProvider} beans only once, at startup. With that approach a tool
 * registered or imported later never appeared to MCP agents, and a killed or circuit-open tool stayed
 * listed until a restart. This reconciler instead adds, replaces and removes tool specifications on the
 * running {@link McpSyncServer}; the MCP SDK then sends {@code notifications/tools/list_changed} to
 * connected agents. It runs every {@code agentgate.mcp.tool-sync-interval} and immediately after a
 * tool or write-tools kill switch.
 * <p>
 * Design Pattern: Observer (kill-switch events) driving a reconciliation loop.
 */
@Component
public class McpToolListSynchronizer {

    public record SyncResult(Set<String> added, Set<String> updated, Set<String> removed) {

        static final SyncResult NONE = new SyncResult(Set.of(), Set.of(), Set.of());

        public boolean changed() {
            return !added.isEmpty() || !updated.isEmpty() || !removed.isEmpty();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(McpToolListSynchronizer.class);

    private final ObjectProvider<McpSyncServer> mcpServerProvider;
    private final ToolRegistryService toolRegistryService;
    private final McpToolCallbackBridge callbackBridge;
    private final ReentrantLock syncLock = new ReentrantLock();

    /** Tools this gateway has published, keyed by name, with the definition fingerprint last sent. */
    private final Map<String, String> publishedFingerprints = new ConcurrentHashMap<>();

    public McpToolListSynchronizer(
            ObjectProvider<McpSyncServer> mcpServerProvider,
            ToolRegistryService toolRegistryService,
            McpToolCallbackBridge callbackBridge
    ) {
        this.mcpServerProvider = mcpServerProvider;
        this.toolRegistryService = toolRegistryService;
        this.callbackBridge = callbackBridge;
    }

    @Scheduled(fixedDelayString = "${agentgate.mcp.tool-sync-interval:5s}")
    public void scheduledSync() {
        synchronizeTools();
    }

    /**
     * FR8: Withdraw a killed tool (or every write tool) from the MCP list straight away
     * rather than waiting for the next scheduled pass.
     */
    @EventListener
    public void onKillSwitchActivated(KillSwitchActivatedEvent event) {
        if (event.scope() != KillSwitchActivatedEvent.Scope.AGENT) {
            synchronizeTools();
        }
    }

    /**
     * Reconciles the MCP server with the tools that are enabled, not write-killed and healthy.
     * Never throws: a failed pass is logged and retried on the next schedule.
     */
    public SyncResult synchronizeTools() {
        McpSyncServer server = mcpServerProvider.getIfAvailable();
        if (server == null) {
            return SyncResult.NONE;
        }

        syncLock.lock();
        try {
            Map<String, ToolResponse> desired = toolRegistryService.listAvailableToolsForAgents().stream()
                    .collect(Collectors.toMap(ToolResponse::name, Function.identity(), (first, second) -> first,
                            LinkedHashMap::new));
            Set<String> listed = server.listTools().stream()
                    .map(McpSchema.Tool::name)
                    .collect(Collectors.toSet());

            Set<String> added = new TreeSet<>();
            Set<String> updated = new TreeSet<>();
            Set<String> removed = new TreeSet<>();

            for (ToolResponse tool : desired.values()) {
                String fingerprint = fingerprint(tool);
                boolean alreadyListed = listed.contains(tool.name());
                if (alreadyListed && fingerprint.equals(publishedFingerprints.get(tool.name()))) {
                    continue;
                }
                try {
                    // addTool replaces an existing tool with the same name, so this also publishes edits
                    server.addTool(McpToolUtils.toSyncToolSpecification(callbackBridge.createGovernedToolCallback(tool)));
                    publishedFingerprints.put(tool.name(), fingerprint);
                    (alreadyListed ? updated : added).add(tool.name());
                } catch (RuntimeException ex) {
                    log.warn("Could not publish tool '{}' on the MCP server: {}", tool.name(), ex.getMessage());
                }
            }

            // Only withdraw tools this gateway published; anything else on the server is left alone.
            for (String name : Set.copyOf(publishedFingerprints.keySet())) {
                if (desired.containsKey(name)) {
                    continue;
                }
                try {
                    if (listed.contains(name)) {
                        server.removeTool(name);
                    }
                    publishedFingerprints.remove(name);
                    removed.add(name);
                } catch (RuntimeException ex) {
                    log.warn("Could not withdraw tool '{}' from the MCP server: {}", name, ex.getMessage());
                }
            }

            SyncResult result = new SyncResult(
                    Collections.unmodifiableSet(added),
                    Collections.unmodifiableSet(updated),
                    Collections.unmodifiableSet(removed));
            if (result.changed()) {
                log.info("MCP tool list synchronised: added={}, updated={}, removed={}", added, updated, removed);
            }
            return result;
        } catch (RuntimeException ex) {
            log.warn("MCP tool list synchronisation skipped; will retry on the next pass: {}", ex.getMessage());
            return SyncResult.NONE;
        } finally {
            syncLock.unlock();
        }
    }

    private static String fingerprint(ToolResponse tool) {
        return tool.description() + '\u0000' + tool.inputSchema();
    }
}
