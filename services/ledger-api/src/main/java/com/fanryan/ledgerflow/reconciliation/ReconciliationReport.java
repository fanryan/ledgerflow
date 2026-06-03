package com.fanryan.ledgerflow.reconciliation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReconciliationReport(
        UUID id,
        String reportType,
        ReconciliationReportStatus status,
        long checkedTransactions,
        long imbalanceCount,
        String details,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}