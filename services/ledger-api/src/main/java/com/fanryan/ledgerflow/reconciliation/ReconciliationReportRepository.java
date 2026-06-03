package com.fanryan.ledgerflow.reconciliation;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationReportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReconciliationReportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(ReconciliationReport report) {
        String sql = """
                INSERT INTO reconciliation_reports (
                    id,
                    report_type,
                    status,
                    checked_transactions,
                    imbalance_count,
                    details,
                    started_at,
                    completed_at
                )
                VALUES (
                    :id,
                    :reportType,
                    :status,
                    :checkedTransactions,
                    :imbalanceCount,
                    CAST(:details AS jsonb),
                    :startedAt,
                    :completedAt
                )
                """;

        jdbcTemplate.update(sql, Map.of(
                "id", report.id(),
                "reportType", report.reportType(),
                "status", report.status().name(),
                "checkedTransactions", report.checkedTransactions(),
                "imbalanceCount", report.imbalanceCount(),
                "details", report.details(),
                "startedAt", report.startedAt(),
                "completedAt", report.completedAt()
        ));
    }

    public int countById(UUID id) {
        String sql = """
                SELECT COUNT(*)
                FROM reconciliation_reports
                WHERE id = :id
                """;

        return jdbcTemplate.queryForObject(
                sql,
                Map.of("id", id),
                Integer.class
        );
    }

    public long countLedgerEntryImbalances() {
        String sql = """
                SELECT COUNT(*)
                FROM (
                    SELECT
                        transaction_id,
                        SUM(
                            CASE
                                WHEN direction = 'DEBIT' THEN amount_minor
                                WHEN direction = 'CREDIT' THEN -amount_minor
                                ELSE 0
                            END
                        ) AS net_amount
                    FROM ledger_entries
                    GROUP BY transaction_id
                    HAVING SUM(
                        CASE
                            WHEN direction = 'DEBIT' THEN amount_minor
                            WHEN direction = 'CREDIT' THEN -amount_minor
                            ELSE 0
                        END
                    ) <> 0
                ) imbalanced_transactions
                """;

        Long count = jdbcTemplate.queryForObject(sql, Map.of(), Long.class);

        return count == null ? 0 : count;
    }

    public long countPostedTransactions() {
        String sql = """
                SELECT COUNT(*)
                FROM transactions
                WHERE status = 'POSTED'
                """;

        Long count = jdbcTemplate.queryForObject(sql, Map.of(), Long.class);

        return count == null ? 0 : count;
    }
}