package com.cse444.agentgate.audit.service;

import com.cse444.agentgate.audit.domain.AuditLogEntry;
import com.cse444.agentgate.audit.domain.AuditLogRepository;
import com.cse444.agentgate.audit.dto.AuditChainVerificationReport;
import com.cse444.agentgate.audit.dto.AuditLogResponse;
import com.cse444.agentgate.shared.event.ToolCallAuditRecordedEvent;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tamper-Evident Audit Trail Service (FR7, QA3).
 * Design Pattern: Observer Pattern via @TransactionalEventListener + Ordered Single-Writer Batch Chain.
 * Enqueues ToolCallAuditRecordedEvent without blocking gateway latency or pinning Java 21 Virtual Threads,
 * persists sequential HMAC-SHA256 hash-chained batches in deterministic order inside TransactionTemplate,
 * and flushes pending events prior to auditor search, export, or integrity verification.
 * When PostgreSQL is unreachable (QA4) a failed batch is put back at the head of the queue in its original
 * order and retried with a short back-off, so calls made during the outage are still hash-chained afterwards.
 */
@Service
public class AuditTrailService {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailService.class);
    private static final int MAX_BATCH_SIZE = 128;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

    private final AuditLogRepository auditRepository;
    private final AuditHashChainCalculator hashCalculator;
    private final TransactionTemplate requiresNewTxTemplate;

    private final ConcurrentLinkedDeque<ToolCallAuditRecordedEvent> pendingQueue = new ConcurrentLinkedDeque<>();
    private final ExecutorService auditWriterExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "agentgate-audit-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantLock writerLock = new ReentrantLock();

    private long cachedLastSequence = -1L;
    private String cachedLastHash = AuditLogEntry.GENESIS_HASH;
    /** While the store is failing, per-event drains wait until the back-off deadline instead of hammering it. */
    private volatile boolean storeFailing = false;
    private volatile long retryNotBeforeNanos;

    public AuditTrailService(
            AuditLogRepository auditRepository,
            AuditHashChainCalculator hashCalculator,
            PlatformTransactionManager transactionManager
    ) {
        this.auditRepository = auditRepository;
        this.hashCalculator = hashCalculator;
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * FR7: Enqueues every tool call event for sequential HMAC-SHA256 hash-chain persistence.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    public void recordAuditEvent(ToolCallAuditRecordedEvent event) {
        if (event == null) {
            return;
        }
        pendingQueue.add(event);
        if (!auditWriterExecutor.isShutdown()) {
            auditWriterExecutor.execute(this::drainBatchSafely);
        }
    }

    /**
     * Ensures all queued audit events are committed to PostgreSQL before reads/verification.
     */
    public void flushPendingAudits() {
        if (auditWriterExecutor.isShutdown()) {
            drainAllRemainingSafely();
            return;
        }
        CompletableFuture.runAsync(this::drainAllRemainingSafely, auditWriterExecutor).join();
    }

    /**
     * QA4: Retries audit events queued during a database outage even when no new calls arrive.
     */
    @Scheduled(fixedDelayString = "2000")
    public void retryQueuedAudits() {
        if (!pendingQueue.isEmpty() && !inRetryBackoff() && !auditWriterExecutor.isShutdown()) {
            auditWriterExecutor.execute(this::drainAllRemainingSafely);
        }
    }

    @PreDestroy
    public void shutdown() {
        drainAllRemainingSafely();
        if (!pendingQueue.isEmpty()) {
            log.error("Shutting down with {} audit events that could not be persisted", pendingQueue.size());
        }
        auditWriterExecutor.shutdown();
    }

    private void drainBatchSafely() {
        if (pendingQueue.isEmpty() || inRetryBackoff()) {
            return;
        }
        writerLock.lock();
        try {
            persistNextBatch();
        } finally {
            writerLock.unlock();
        }
    }

    private void drainAllRemainingSafely() {
        writerLock.lock();
        try {
            while (!pendingQueue.isEmpty() && persistNextBatch()) {
                // keep draining until the queue is empty or the store fails again
            }
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Persists the next batch. On failure the batch goes back to the head of the queue in its original
     * order, the cached chain tail is discarded and further per-event attempts back off (FR7, QA4).
     * Must be called while holding {@code writerLock}.
     */
    private boolean persistNextBatch() {
        List<ToolCallAuditRecordedEvent> batch = drainUpTo(MAX_BATCH_SIZE);
        if (batch.isEmpty()) {
            return true;
        }
        try {
            persistHashChainedBatch(batch);
            if (storeFailing) {
                storeFailing = false;
                log.info("Audit store reachable again; resumed hash-chain writes ({} events still queued)", pendingQueue.size());
            }
            return true;
        } catch (Exception ex) {
            for (int i = batch.size() - 1; i >= 0; i--) {
                pendingQueue.addFirst(batch.get(i));
            }
            resetTailCache();
            retryNotBeforeNanos = System.nanoTime() + RETRY_BACKOFF.toNanos();
            storeFailing = true;
            log.warn("Audit store unavailable; keeping {} audit events in order for retry (FR7, QA4): {}",
                    pendingQueue.size(), ex.getMessage());
            return false;
        }
    }

    private boolean inRetryBackoff() {
        return storeFailing && System.nanoTime() - retryNotBeforeNanos < 0;
    }

    private List<ToolCallAuditRecordedEvent> drainUpTo(int max) {
        List<ToolCallAuditRecordedEvent> batch = new ArrayList<>(Math.min(max, 16));
        for (int i = 0; i < max; i++) {
            ToolCallAuditRecordedEvent item = pendingQueue.poll();
            if (item == null) {
                break;
            }
            batch.add(item);
        }
        return batch;
    }

    private void persistHashChainedBatch(List<ToolCallAuditRecordedEvent> batch) {
        requiresNewTxTemplate.executeWithoutResult(status -> {
            if (cachedLastSequence < 0L) {
                Optional<AuditLogEntry> latest = auditRepository.findTopByOrderBySequenceNumberDesc();
                cachedLastSequence = latest.map(AuditLogEntry::getSequenceNumber).orElse(0L);
                cachedLastHash = latest.map(AuditLogEntry::getEntryHash).orElse(AuditLogEntry.GENESIS_HASH);
            }

            List<AuditLogEntry> entries = new ArrayList<>(batch.size());
            long seq = cachedLastSequence;
            String prevHash = cachedLastHash;

            for (ToolCallAuditRecordedEvent event : batch) {
                seq++;
                Instant truncatedOccurredAt = (event.occurredAt() != null ? event.occurredAt() : Instant.now())
                        .truncatedTo(ChronoUnit.MILLIS);
                UUID entryId = event.eventId() != null ? event.eventId() : UUID.randomUUID();

                String entryHash = hashCalculator.computeEntryHash(
                        seq,
                        entryId,
                        event.traceId(),
                        event.agentClientId(),
                        event.delegatedUserId(),
                        event.toolName(),
                        event.redactedArgumentsJson(),
                        event.policyRuleName(),
                        event.policyRuleVersion(),
                        event.approvalId(),
                        event.approvalState(),
                        event.outcome(),
                        event.latencyMs(),
                        truncatedOccurredAt,
                        prevHash
                );

                entries.add(new AuditLogEntry(
                        entryId,
                        seq,
                        event.traceId(),
                        event.agentClientId(),
                        event.delegatedUserId(),
                        event.toolName(),
                        event.redactedArgumentsJson(),
                        event.policyRuleName(),
                        event.policyRuleVersion(),
                        event.approvalId(),
                        event.approvalState(),
                        event.outcome(),
                        event.latencyMs(),
                        truncatedOccurredAt,
                        prevHash,
                        entryHash
                ));
                prevHash = entryHash;
            }

            auditRepository.saveAllAndFlush(entries);
            cachedLastSequence = seq;
            cachedLastHash = prevHash;
        });
    }

    private void resetTailCache() {
        cachedLastSequence = -1L;
        cachedLastHash = AuditLogEntry.GENESIS_HASH;
    }

    /**
     * QA3 Verify Command: Recomputes every entry's hash and verifies previousHash linkage.
     * Editing any column of any stored audit row or deleting a row is immediately detected.
     */
    @Transactional(readOnly = true)
    public AuditChainVerificationReport verifyChainIntegrity() {
        flushPendingAudits();
        List<AuditLogEntry> allEntries = auditRepository.findAllByOrderBySequenceNumberAsc();
        String expectedPreviousHash = AuditLogEntry.GENESIS_HASH;
        long expectedSeq = 1L;

        for (AuditLogEntry entry : allEntries) {
            if (entry.getSequenceNumber() != expectedSeq) {
                return new AuditChainVerificationReport(
                        false,
                        expectedSeq - 1,
                        entry.getSequenceNumber(),
                        entry.getId(),
                        "Sequence gap detected: expected sequence " + expectedSeq + " but found " + entry.getSequenceNumber(),
                        Instant.now()
                );
            }

            if (!expectedPreviousHash.equals(entry.getPreviousHash())) {
                return new AuditChainVerificationReport(
                        false,
                        expectedSeq - 1,
                        entry.getSequenceNumber(),
                        entry.getId(),
                        "Broken previous_hash link at sequence " + entry.getSequenceNumber(),
                        Instant.now()
                );
            }

            Instant truncatedOccurredAt = entry.getOccurredAt().truncatedTo(ChronoUnit.MILLIS);
            String recomputedHash = hashCalculator.computeEntryHash(
                    entry.getSequenceNumber(),
                    entry.getId(),
                    entry.getTraceId(),
                    entry.getAgentClientId(),
                    entry.getDelegatedUserId(),
                    entry.getToolName(),
                    entry.getRedactedArgumentsJson(),
                    entry.getPolicyRuleName(),
                    entry.getPolicyRuleVersion(),
                    entry.getApprovalId(),
                    entry.getApprovalState(),
                    entry.getOutcome(),
                    entry.getLatencyMs(),
                    truncatedOccurredAt,
                    entry.getPreviousHash()
            );

            if (!recomputedHash.equals(entry.getEntryHash())) {
                return new AuditChainVerificationReport(
                        false,
                        expectedSeq - 1,
                        entry.getSequenceNumber(),
                        entry.getId(),
                        "Tampering detected at sequence " + entry.getSequenceNumber()
                                + " (id=" + entry.getId() + "): stored entry_hash does not match recomputed HMAC-SHA256 digest",
                        Instant.now()
                );
            }

            expectedPreviousHash = entry.getEntryHash();
            expectedSeq++;
        }

        return new AuditChainVerificationReport(
                true,
                allEntries.size(),
                null,
                null,
                "All hash-chained audit records verified intact",
                Instant.now()
        );
    }

    /**
     * FR7 & QA3: Multi-criteria paginated search so an auditor finds any call in seconds.
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> searchAuditLogs(
            String agentClientId,
            String delegatedUserId,
            String toolName,
            String outcome,
            String policyRuleName,
            Pageable pageable
    ) {
        flushPendingAudits();
        Specification<AuditLogEntry> spec = buildSearchSpecification(
                agentClientId, delegatedUserId, toolName, outcome, policyRuleName);
        return auditRepository.findAll(spec, pageable).map(AuditLogResponse::from);
    }

    /**
     * FR7: Exports matching audit records as CSV for compliance reporting.
     */
    @Transactional(readOnly = true)
    public String exportAuditLogsAsCsv(
            String agentClientId,
            String delegatedUserId,
            String toolName,
            String outcome
    ) {
        flushPendingAudits();
        Specification<AuditLogEntry> spec = buildSearchSpecification(
                agentClientId, delegatedUserId, toolName, outcome, null);
        List<AuditLogEntry> rows = auditRepository.findAll(spec);

        StringBuilder csv = new StringBuilder();
        csv.append("sequence_number,id,trace_id,agent_client_id,delegated_user_id,tool_name,policy_rule_name,policy_rule_version,approval_id,approval_state,outcome,latency_ms,occurred_at,previous_hash,entry_hash\n");
        for (AuditLogEntry r : rows) {
            csv.append(r.getSequenceNumber()).append(',')
                    .append(r.getId()).append(',')
                    .append(r.getTraceId()).append(',')
                    .append(r.getAgentClientId()).append(',')
                    .append(r.getDelegatedUserId() != null ? r.getDelegatedUserId() : "").append(',')
                    .append(r.getToolName()).append(',')
                    .append(r.getPolicyRuleName()).append(',')
                    .append(r.getPolicyRuleVersion()).append(',')
                    .append(r.getApprovalId() != null ? r.getApprovalId() : "").append(',')
                    .append(r.getApprovalState() != null ? r.getApprovalState() : "").append(',')
                    .append(r.getOutcome()).append(',')
                    .append(r.getLatencyMs()).append(',')
                    .append(r.getOccurredAt()).append(',')
                    .append(r.getPreviousHash()).append(',')
                    .append(r.getEntryHash()).append('\n');
        }
        return csv.toString();
    }

    private Specification<AuditLogEntry> buildSearchSpecification(
            String agentClientId,
            String delegatedUserId,
            String toolName,
            String outcome,
            String policyRuleName
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (agentClientId != null && !agentClientId.isBlank()) {
                predicates.add(cb.equal(root.get("agentClientId"), agentClientId));
            }
            if (delegatedUserId != null && !delegatedUserId.isBlank()) {
                predicates.add(cb.equal(root.get("delegatedUserId"), delegatedUserId));
            }
            if (toolName != null && !toolName.isBlank()) {
                predicates.add(cb.equal(root.get("toolName"), toolName));
            }
            if (outcome != null && !outcome.isBlank()) {
                predicates.add(cb.equal(root.get("outcome"), outcome));
            }
            if (policyRuleName != null && !policyRuleName.isBlank()) {
                predicates.add(cb.equal(root.get("policyRuleName"), policyRuleName));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
