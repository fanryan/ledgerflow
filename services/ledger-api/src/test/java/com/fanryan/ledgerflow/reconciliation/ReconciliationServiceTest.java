package com.fanryan.ledgerflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ReconciliationServiceTest {

    @Test
    void runLedgerBalanceCheckReturnsPassedWhenNoImbalancesExist() {
        ReconciliationReportRepository repository = mock(ReconciliationReportRepository.class);

        when(repository.countPostedTransactions()).thenReturn(5L);
        when(repository.countLedgerEntryImbalances()).thenReturn(0L);

        ReconciliationService service = new ReconciliationService(repository);

        ReconciliationReport report = service.runLedgerBalanceCheck();

        assertThat(report.status()).isEqualTo(ReconciliationReportStatus.PASSED);
        assertThat(report.checkedTransactions()).isEqualTo(5);
        assertThat(report.imbalanceCount()).isZero();

        verify(repository).save(report);
    }

    @Test
    void runLedgerBalanceCheckReturnsFailedWhenImbalancesExist() {
        ReconciliationReportRepository repository = mock(ReconciliationReportRepository.class);

        when(repository.countPostedTransactions()).thenReturn(5L);
        when(repository.countLedgerEntryImbalances()).thenReturn(2L);

        ReconciliationService service = new ReconciliationService(repository);

        ReconciliationReport report = service.runLedgerBalanceCheck();

        assertThat(report.status()).isEqualTo(ReconciliationReportStatus.FAILED);
        assertThat(report.checkedTransactions()).isEqualTo(5);
        assertThat(report.imbalanceCount()).isEqualTo(2);

        verify(repository).save(report);
    }

    @Test
    void runAccountBalanceCheckReturnsPassedWhenNoMismatchesExist() {
        ReconciliationReportRepository repository = mock(ReconciliationReportRepository.class);

        when(repository.countUserAccounts()).thenReturn(3L);
        when(repository.countAccountBalanceMismatches()).thenReturn(0L);

        ReconciliationService service = new ReconciliationService(repository);

        ReconciliationReport report = service.runAccountBalanceCheck();

        assertThat(report.reportType()).isEqualTo("ACCOUNT_BALANCE_CHECK");
        assertThat(report.status()).isEqualTo(ReconciliationReportStatus.PASSED);
        assertThat(report.checkedTransactions()).isEqualTo(3);
        assertThat(report.imbalanceCount()).isZero();
        assertThat(report.details()).contains("account_balance_equals_ledger_derived_balance");

        verify(repository).save(report);
    }

    @Test
    void runAccountBalanceCheckReturnsFailedWhenMismatchesExist() {
        ReconciliationReportRepository repository = mock(ReconciliationReportRepository.class);

        when(repository.countUserAccounts()).thenReturn(3L);
        when(repository.countAccountBalanceMismatches()).thenReturn(1L);

        ReconciliationService service = new ReconciliationService(repository);

        ReconciliationReport report = service.runAccountBalanceCheck();

        assertThat(report.reportType()).isEqualTo("ACCOUNT_BALANCE_CHECK");
        assertThat(report.status()).isEqualTo(ReconciliationReportStatus.FAILED);
        assertThat(report.checkedTransactions()).isEqualTo(3);
        assertThat(report.imbalanceCount()).isEqualTo(1);

        verify(repository).save(report);
    }
}
