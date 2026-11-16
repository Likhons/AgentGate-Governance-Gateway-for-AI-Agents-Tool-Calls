package com.cse444.agentgate.registry.adapter;

import com.cse444.agentgate.registry.adapter.DownstreamToolAdapter.DownstreamExecutionResult;
import com.cse444.agentgate.registry.domain.RegisteredTool;
import com.cse444.agentgate.shared.domain.ToolType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Design Pattern: Proxy / Decorator Pattern (FR10).
 * Wraps DownstreamToolAdapter calls in a per-tool Resilience4j CircuitBreaker and TimeLimiter
 * running on Java 21 Virtual Threads. Also exposes circuit state so tools whose circuit is OPEN
 * or FORCED_OPEN are automatically hidden from agents' MCP tool discovery lists until recovery.
 */
@Component
public class ResilientDownstreamInvoker {

    private final Map<ToolType, DownstreamToolAdapter> adaptersByType;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ExecutorService virtualThreadExecutor;

    public ResilientDownstreamInvoker(
            List<DownstreamToolAdapter> adapters,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this.adaptersByType = adapters.stream()
                .collect(Collectors.toMap(DownstreamToolAdapter::supportsType, Function.identity()));
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Executes a downstream tool with per-tool timeout and circuit breaker protection (FR10).
     */
    public DownstreamExecutionResult invokeWithResilience(RegisteredTool tool, Map<String, Object> arguments) {
        DownstreamToolAdapter adapter = adaptersByType.get(tool.getToolType());
        if (adapter == null) {
            throw new IllegalStateException("No downstream adapter registered for tool type: " + tool.getToolType());
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(tool.getName());
        TimeLimiterConfig perToolTimeoutConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(tool.getTimeoutMs()))
                .cancelRunningFuture(true)
                .build();
        TimeLimiter timeLimiter = TimeLimiter.of(tool.getName(), perToolTimeoutConfig);

        Callable<DownstreamExecutionResult> timedCall = TimeLimiter.decorateFutureSupplier(
                timeLimiter,
                () -> CompletableFuture.supplyAsync(() -> adapter.invoke(tool, arguments), virtualThreadExecutor)
        );
        Callable<DownstreamExecutionResult> resilientCall =
                CircuitBreaker.decorateCallable(circuitBreaker, timedCall);

        try {
            return resilientCall.call();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Downstream tool '" + tool.getName() + "' failed or timed out (circuitState="
                            + circuitBreaker.getState() + "): " + ex.getMessage(),
                    ex
            );
        }
    }

    /**
     * Returns true when a tool's circuit breaker is CLOSED or HALF_OPEN.
     * Tools in OPEN or FORCED_OPEN state are considered down and hidden from agents (FR10).
     */
    public boolean isToolHealthy(String toolName) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(toolName);
        CircuitBreaker.State state = cb.getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    public String getCircuitState(String toolName) {
        return circuitBreakerRegistry.circuitBreaker(toolName).getState().name();
    }
}
