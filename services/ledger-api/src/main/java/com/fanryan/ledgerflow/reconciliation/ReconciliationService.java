package com.fanryan.ledgerflow.reconciliation;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    private static final String LEDGER_BALANCE_CHECK = "LEDGER_BALANCE_CHECK";

    private final ReconciliationReportRepository reconciliationReportRepository;

    public ReconciliationService(ReconciliationReportRepository reconciliationReportRepository) {
        this.reconciliationReportRepository = reconciliationReportRepository;
    }

    @Transactional
    public ReconciliationReport runLedgerBalanceCheck() {
        OffsetDateTime startedAt = OffsetDateTime.now();

        long checkedTransactions = reconciliationReportRepository.countPostedTransactions();
        long imbalanceCount = reconciliationReportRepository.countLedgerEntryImbalances();

        ReconciliationReportStatus status = imbalanceCount == 0
                ? ReconciliationReportStatus.PASSED
                : ReconciliationReportStatus.FAILED;

        OffsetDateTime completedAt = OffsetDateTime.now();

        ReconciliationReport report = new ReconciliationReport(
                UUID.randomUUID(),
                LEDGER_BALANCE_CHECK,
                status,
                checkedTransactions,
                imbalanceCount,
                """
                        {"check":"total_debits_equal_total_credits"}
                        """,
                startedAt,
                completedAt
        );

        reconciliationReportRepository.save(report);

        return report;
    }
}