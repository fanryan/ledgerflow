# Reconciliation

This document explains the current LedgerFlow reconciliation foundation. The implemented checks validate both double-entry ledger balance and stored account balance drift, then persist reconciliation reports for auditability.

## Current Scope

Implemented:

- `reconciliation_reports` table through `V11__create_reconciliation_reports_table.sql`.
- Authenticated `POST /reconciliation/ledger-balance-check`.
- Authenticated `POST /reconciliation/account-balance-check`.
- `ReconciliationService.runLedgerBalanceCheck()`.
- `ReconciliationService.runAccountBalanceCheck()`.
- `ReconciliationReportRepository`.
- `ReconciliationReport` and `ReconciliationReportStatus`.
- Report persistence with JSONB `details`.
- Ledger-entry imbalance detection per posted transaction.
- Account balance mismatch detection comparing `accounts.balance_minor` against ledger-derived balances.
- Tests for repository persistence, service pass/fail decisions, endpoint authentication, and endpoint report creation.
- Manual verification showing `LEDGER_BALANCE_CHECK` passed with `checkedTransactions = 1006` and `imbalanceCount = 0`.

Not implemented yet:

- Scheduled reconciliation.
- Listing historical reconciliation reports.
- Returning imbalanced transaction ids or mismatched account ids in report details.
- Comparing PostgreSQL ledger state against external systems.
- Balance snapshots.

## Runtime Flow

```text
Client
  |
  | POST /reconciliation/ledger-balance-check
  | POST /reconciliation/account-balance-check
  | Authorization: Bearer <access_token>
  v
ReconciliationController
  |
  v
ReconciliationService
  |
  +--> run requested reconciliation query
  +--> count checked items
  +--> count imbalances or mismatches
  +--> create PASSED or FAILED report
  |
  v
ReconciliationReportRepository
  |
  v
PostgreSQL reconciliation_reports
```

Both endpoints are protected by the default Spring Security rule:

```text
anyRequest().authenticated()
```

## Ledger Balance Check

`POST /reconciliation/ledger-balance-check` validates the double-entry invariant for each transaction:

```text
total DEBIT amount == total CREDIT amount
```

The repository query groups ledger entries by `transaction_id` and calculates:

```text
DEBIT amounts - CREDIT amounts
```

If the result is not zero, that transaction is imbalanced.

Current report type:

```text
LEDGER_BALANCE_CHECK
```

Current details payload:

```json
{
  "check": "total_debits_equal_total_credits"
}
```

## Account Balance Check

`POST /reconciliation/account-balance-check` validates that each normal user account's stored `accounts.balance_minor` matches the balance derived from `ledger_entries`.

The derived balance calculation uses the user-account side of the ledger:

```text
CREDIT amount
-DEBIT amount
= ledger-derived account balance
```

The check excludes `SystemAccounts.USD_SETTLEMENT_ACCOUNT_ID`. The settlement account is currently used as the offsetting system account for deposits, withdrawals, and reversals, but its stored account balance is not updated as part of transaction posting. Excluding it keeps this check focused on user-facing account balance drift.

Current report type:

```text
ACCOUNT_BALANCE_CHECK
```

Current details payload:

```json
{
  "check": "account_balance_equals_ledger_derived_balance",
  "excludedAccounts": ["USD_SETTLEMENT"]
}
```

## Report Fields

`reconciliation_reports` stores:

- `report_type`: currently `LEDGER_BALANCE_CHECK` or `ACCOUNT_BALANCE_CHECK`.
- `status`: `PASSED` or `FAILED`.
- `checked_transactions`: count of checked items. For `LEDGER_BALANCE_CHECK`, this is posted transactions. For `ACCOUNT_BALANCE_CHECK`, this is checked user accounts.
- `imbalance_count`: count of failed items. For `LEDGER_BALANCE_CHECK`, this is imbalanced transactions. For `ACCOUNT_BALANCE_CHECK`, this is account balance mismatches.
- `details`: JSONB metadata for the check.
- `started_at` and `completed_at`: run timing.

The column names are transaction-oriented because the table started with the ledger balance check. The account-balance check reuses them as generic summary counts instead of adding schema churn late in the project.

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

Defines `POST /reconciliation/ledger-balance-check` and `POST /reconciliation/account-balance-check`.

`ReconciliationService.java`

Coordinates each check, creates the report, and saves it transactionally.

`ReconciliationReportRepository.java`

Uses explicit SQL through `NamedParameterJdbcTemplate`. This is intentional because the repository writes JSONB report details and owns report-specific aggregate queries.

`ReconciliationReport.java`

Data carrier for one report row.

`ReconciliationReportStatus.java`

Enum containing `PASSED` and `FAILED`.

`V11__create_reconciliation_reports_table.sql`

Creates the report table and indexes for newest-first report lookup.

## Interview Notes

1. **What does reconciliation mean here?**  
   It means independently checking ledger invariants after transaction posting.

2. **What invariants are checked today?**  
   Posted transactions must have equal debit and credit totals, and normal user account balances must match ledger-derived balances.

3. **Why store reconciliation reports?**  
   They create an audit trail of when checks ran and what result they produced.

4. **Why use JSONB for details?**  
   Report details can evolve without changing the main summary columns each time.

5. **What does `imbalanceCount = 0` prove?**  
   It proves no checked item violated the selected reconciliation invariant.

6. **Why exclude the settlement account from account-balance reconciliation?**  
   LedgerFlow currently uses it as the offsetting account for double-entry rows, but only user account balances are updated in the transaction path.

7. **What is still missing?**  
   Richer mismatch details, scheduled runs, report listing, external-state comparison, and balance snapshots.

## Checklist

- [ ] Explain why reconciliation is separate from transaction posting.
- [ ] Explain how the ledger balance check calculates transaction imbalances.
- [ ] Explain how the account balance check detects stored-balance drift.
- [ ] Explain why the USD settlement system account is excluded today.
- [ ] Explain why reports are persisted.
- [ ] Explain what `PASSED` and `FAILED` mean.
- [ ] Explain what richer reconciliation would add beyond the current summary counts.
