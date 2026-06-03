# Reconciliation

This document explains the current LedgerFlow reconciliation foundation. The implemented slice checks whether posted transactions have balanced ledger entries and stores a reconciliation report.

## Current Scope

Implemented:

- `reconciliation_reports` table through `V11__create_reconciliation_reports_table.sql`.
- Authenticated `POST /reconciliation/ledger-balance-check`.
- `ReconciliationService.runLedgerBalanceCheck()`.
- `ReconciliationReportRepository`.
- `ReconciliationReport` and `ReconciliationReportStatus`.
- Report persistence with JSONB `details`.
- Tests for repository persistence, service pass/fail decisions, endpoint authentication, and endpoint report creation.
- Manual verification showing `PASSED` with `checkedTransactions = 1006` and `imbalanceCount = 0`.

Not implemented yet:

- Scheduled reconciliation.
- Listing historical reconciliation reports.
- Returning imbalanced transaction ids in report details.
- Comparing PostgreSQL ledger state against external or derived systems.
- Dead-letter replay.

## Runtime Flow

```text
Client
  |
  | POST /reconciliation/ledger-balance-check
  | Authorization: Bearer <access_token>
  v
ReconciliationController
  |
  v
ReconciliationService
  |
  +--> count posted transactions
  +--> count transactions whose ledger entries do not balance
  +--> create PASSED or FAILED report
  |
  v
ReconciliationReportRepository
  |
  v
PostgreSQL reconciliation_reports
```

The endpoint is protected by the default Spring Security rule:

```text
anyRequest().authenticated()
```

## Ledger Balance Check

The current check validates the double-entry invariant for each transaction:

```text
total DEBIT amount == total CREDIT amount
```

The repository query groups ledger entries by `transaction_id` and calculates:

```text
DEBIT amounts - CREDIT amounts
```

If the result is not zero, that transaction is imbalanced.

## Report Fields

`reconciliation_reports` stores:

- `report_type`: currently `LEDGER_BALANCE_CHECK`.
- `status`: `PASSED` or `FAILED`.
- `checked_transactions`: number of posted transactions checked.
- `imbalance_count`: number of imbalanced transactions.
- `details`: JSONB metadata for the check.
- `started_at` and `completed_at`: run timing.

Current details payload:

```json
{
  "check": "total_debits_equal_total_credits"
}
```

## Manual Verification

Manual verification returned:

```json
{
  "reportType": "LEDGER_BALANCE_CHECK",
  "status": "PASSED",
  "checkedTransactions": 1006,
  "imbalanceCount": 0
}
```

The persisted database row also showed:

```text
LEDGER_BALANCE_CHECK | PASSED | 1006 | 0
```

That means all posted transactions in the local database had balanced ledger entries at the time of the check.

## File Guide

`ReconciliationController.java`

Defines `POST /reconciliation/ledger-balance-check`.

`ReconciliationService.java`

Coordinates the check, creates the report, and saves it transactionally.

`ReconciliationReportRepository.java`

Uses explicit SQL through `NamedParameterJdbcTemplate`. This is intentional because the repository writes JSONB report details and owns report-specific SQL queries.

`ReconciliationReport.java`

Data carrier for one report row.

`ReconciliationReportStatus.java`

Enum containing `PASSED` and `FAILED`.

`V11__create_reconciliation_reports_table.sql`

Creates the report table and indexes for newest-first report lookup.

## Interview Notes

1. **What does reconciliation mean here?**  
   It means independently checking ledger invariants after transaction posting.

2. **What invariant is checked today?**  
   Each posted transaction must have equal debit and credit totals.

3. **Why store reconciliation reports?**  
   They create an audit trail of when checks ran and what result they produced.

4. **Why use JSONB for details?**  
   Report details can evolve without changing the main summary columns each time.

5. **What does `imbalanceCount = 0` prove?**  
   It proves no checked transaction violated the current debit/credit balance invariant.

6. **What is still missing?**  
   Richer details, scheduled runs, report listing, external-state comparison, and dead-letter replay.

## Checklist

- [ ] Explain why reconciliation is separate from transaction posting.
- [ ] Explain how the ledger balance check calculates imbalances.
- [ ] Explain why reports are persisted.
- [ ] Explain what `PASSED` and `FAILED` mean.
- [ ] Explain why this is only the first reconciliation slice.
