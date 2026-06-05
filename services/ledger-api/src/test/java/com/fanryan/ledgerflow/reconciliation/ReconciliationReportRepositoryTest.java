package com.fanryan.ledgerflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.fanryan.ledgerflow.account.Account;
import com.fanryan.ledgerflow.account.AccountRepository;
import com.fanryan.ledgerflow.account.AccountStatus;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;

class ReconciliationReportRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private ReconciliationReportRepository repository;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void clearReports() {
        jdbcTemplate.update("DELETE FROM reconciliation_reports", Map.of());
    }

    @Test
    void saveStoresReconciliationReport() {
        UUID reportId = UUID.randomUUID();
        OffsetDateTime startedAt = OffsetDateTime.now().minusSeconds(1);
        OffsetDateTime completedAt = OffsetDateTime.now();

        ReconciliationReport report = new ReconciliationReport(
                reportId,
                "LEDGER_BALANCE_CHECK",
                ReconciliationReportStatus.PASSED,
                10,
                0,
                """
                        {"imbalances":[]}
                        """,
                startedAt,
                completedAt
        );

        repository.save(report);

        int count = repository.countById(reportId);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void countAccountBalanceMismatchesFindsStoredBalanceDrift() {
        UUID ownerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        OffsetDateTime now = OffsetDateTime.now();
        long beforeMismatchCount = repository.countAccountBalanceMismatches();
        long beforeUserAccountCount = repository.countUserAccounts();

        accountRepository.save(new Account(
                UUID.randomUUID(),
                ownerUserId,
                "USD",
                AccountStatus.ACTIVE,
                123,
                0,
                now,
                now
        ));

        assertThat(repository.countUserAccounts()).isEqualTo(beforeUserAccountCount + 1);
        assertThat(repository.countAccountBalanceMismatches()).isEqualTo(beforeMismatchCount + 1);
    }
}
