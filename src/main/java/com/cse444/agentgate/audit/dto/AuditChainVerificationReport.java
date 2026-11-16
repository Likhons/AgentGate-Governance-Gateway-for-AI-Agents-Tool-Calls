package com.cse444.agentgate.audit.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Output of the Tamper-Evident Audit Chain Verification command (FR7, QA3).
 * Reports whether the entire hash chain is intact, or pinpoints the exact row
 * where tampering (column edit, deletion, or reordering) was detected.
 */
public record AuditChainVerificationReport(
        boolean intact,
        long totalRecordsVerified,
        Long firstTamperedSequenceNumber,
        UUID firstTamperedEntryId,
        String failureReason,
        Instant verifiedAt
) {}
