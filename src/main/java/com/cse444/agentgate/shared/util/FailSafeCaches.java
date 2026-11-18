package com.cse444.agentgate.shared.util;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * "Last known good" caches for hot-path lookups (QA2, QA4).
 * <p>
 * An entry is refreshed in the background once it is older than {@code refreshAfter}. When the refresh
 * fails, for example because PostgreSQL is unreachable, Caffeine keeps serving the previous value, so
 * calls keep working on the last known agent, tool and limit state. An entry that has not been loaded
 * successfully for {@code maxStaleness} expires, which bounds how long stale state is trusted (QA4 allows
 * low-risk tools to run on the last known state for up to 5 minutes).
 * <p>
 * Refreshes run on virtual threads, so a slow or unreachable database never blocks request threads.
 */
public final class FailSafeCaches {

    private static final Executor REFRESH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private FailSafeCaches() {
    }

    public static <K, V> LoadingCache<K, V> lastKnownGood(
            long maximumSize,
            Duration refreshAfter,
            Duration maxStaleness,
            CacheLoader<K, V> loader
    ) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .refreshAfterWrite(refreshAfter)
                .expireAfterWrite(maxStaleness)
                .executor(REFRESH_EXECUTOR)
                .build(loader);
    }
}
