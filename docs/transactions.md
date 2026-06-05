# Transactions

This document explains the current LedgerFlow transaction foundation. It focuses on what exists today: accepting authenticated transaction commands, validating ownership and currency, enforcing idempotency, creating balanced ledger entries, updating account balances, returning `POSTED` transaction rows, recording `FAILED` rows for insufficient funds, reversing posted transactions with offsetting ledger entries, handling concurrent balance updates safely, writing transactional outbox rows for posted transaction events, publishing those outbox events to Kafka through a scheduled claim-based publisher, consuming `ledger.events` into an idempotent consumed-event audit table, and ingesting PayCore payment events as idempotent LedgerFlow transactions.

The current implementation creates two balanced ledger entries per posted transaction: one for the user account and one for the seeded USD settlement system account.

## 1. Current Transaction Scope

### Implemented

The Spring Boot API currently supports:

- `transactions` table through Flyway migration `V4__create_transactions_table.sql`.
- `ledger_entries` table through Flyway migration `V5__create_ledger_entries_table.sql`.
- `idempotency_keys` table through Flyway migration `V7__create_idempotency_keys_table.sql`.
- Reversal metadata through Flyway migration `V8__add_transaction_reversal_fields.sql`.
- `outbox_events` table through Flyway migration `V9__create_outbox_events_table.sql`.
- `consumed_ledger_events` table through Flyway migration `V10__create_consumed_ledger_events_table.sql`.
- Authenticated `POST /transactions`.
- Authenticated `GET /transactions`.
- Authenticated `POST /transactions/{transactionId}/reverse`.
- `Idempotency-Key` request header.
- Idempotency request hash conflict detection.
- Stored idempotency response metadata.
- Account existence check before transaction creation.
- Account ownership check before transaction creation.
- Transaction currency validation against the account currency.
- Request validation for required account id, type, positive amount, currency, and idempotency key.
- Transaction rows created as `PENDING`.
- Successful transactions updated to `POSTED`.
- Deposits increase account balance.
- Withdrawals decrease account balance.
- Frozen and closed accounts cannot submit transactions.
- Insufficient withdrawals return `422 INSUFFICIENT_FUNDS`.
- Insufficient withdrawals are recorded as `FAILED` transactions.
- Balanced ledger entries are written for deposits and withdrawals.
- Reversals create a new offsetting `POSTED` transaction.
- Reversals mark the original transaction with `reversed_at`.
- Reversal transactions point to the original through `reversal_of_transaction_id`.
- Reversal requests require a reason.
- Reversal requests are idempotent through the same `idempotency_keys` table.
- Optimistic locking conflict handling for concurrent account balance updates.
- `409 CONCURRENT_TRANSACTION_CONFLICT` for racing writes against the same account balance.
- `TRANSACTION_POSTED` outbox event creation for successful transaction posting.
- `TRANSACTION_POSTED` outbox event creation for successful reversals.
- Outbox payloads stored as PostgreSQL `jsonb`.
- Claim-based outbox publishing with `FOR UPDATE SKIP LOCKED`.
- Stale outbox claim recovery through `locked_until`.
- Scheduled Spring Boot outbox publisher.
- Kafka publishing to topic `ledger.events`.
- Kafka consumption from topic `ledger.events`.
- Consumed `TRANSACTION_POSTED` events are recorded in `consumed_ledger_events`.
- Duplicate consumed events are ignored with `(transaction_id, event_type)` uniqueness.
- Manual verification that published `TRANSACTION_POSTED` events are consumed and persisted by `LedgerEventsConsumer`.
- Successful publishes mark rows as `PUBLISHED`.
- Failed publishes mark rows as `FAILED` with retry metadata.
- USD settlement system account seeded by `V6__seed_system_account.sql`.
- `SystemAccounts.USD_SETTLEMENT_ACCOUNT_ID` centralizes the settlement account UUID in Java.
- Java models and repositories for `Transaction` and `LedgerEntry`.
- Transaction flow tests for auth, listing, successful submission, idempotency replay, idempotency conflict, invalid amount, currency mismatch, balance updates, insufficient funds, failed transaction recording, idempotent retry balance safety, account-state guards, balanced ledger entries, reversals, double-reversal rejection, and reversal idempotency.
- Transaction concurrency test proving simultaneous withdrawals cannot overdraw an account.
- Outbox event creation test proving a posted transaction creates a pending outbox row.
- Outbox repository tests for claim, publish, and failed-publish transitions.
- Outbox publisher service tests for successful Kafka publish and failed Kafka publish handling.
- Kafka consumer test proving the current event payload is passed to the consumed-event repository.
- Consumed event repository tests proving first insert succeeds and duplicate insert is ignored.
- Ledger balance reconciliation checks posted transactions for debit/credit imbalances.
- Account balance reconciliation checks stored user account balances against ledger-derived balances.
- Reconciliation reports are persisted in `reconciliation_reports`.
- Authenticated `POST /reconciliation/ledger-balance-check` endpoint.
- Authenticated `POST /reconciliation/account-balance-check` endpoint.
- Reconciliation repository, service, and flow tests.
- PayCore consumer for Kafka topic `payment.captured`.
- PayCore consumer for Kafka topic `payment.settled`.
- PayCore `eventId` used as the LedgerFlow transaction idempotency key.
- PayCore payment events submit LedgerFlow `DEPOSIT` transactions.
- PayCore consumer tests for captured and settled payment events.
- Manual verification that PayCore captured and settled events are consumed and posted successfully.
- `dead_letter_events` table through Flyway migration `V12__create_dead_letter_events_table.sql`.
- Invalid PayCore events are persisted as `PENDING` dead-letter rows.
- Authenticated `POST /admin/dead-letter/replay`.
- Dead-letter replay routes PayCore dead letters back through the matching consumer method.
- Replayed dead-letter rows are marked `REPLAYED`.
- Dead-letter repository, replay service, and flow tests.
- Testcontainers PostgreSQL migration smoke test.
- Testcontainers Kafka test proving outbox rows publish to `ledger.events`.
- Testcontainers end-to-end test proving a posted transaction creates an outbox event that publishes to Kafka.
- Testcontainers PayCore tests proving `payment.captured` and `payment.settled` events create LedgerFlow transactions.

### Not Implemented Yet

These are planned, not implemented:

- Richer system-account modeling beyond the seeded USD settlement account.
- Projections, richer reconciliation details, scheduled reconciliation, scheduled dead-letter replay, balance snapshots, and broader external-state comparison.

## 2. Endpoint

### `POST /transactions`

Required header:

```http
Authorization: Bearer <access_token>
Idempotency-Key: tx-example-001
```

Request body:

```json
{
  "accountId": "5e824b14-77a3-4db7-882b-4c06abc2dc8b",
  "type": "DEPOSIT",
  "amountMinor": 1000,
  "currency": "USD",
  "description": "Test deposit"
}
```

Current successful response:

```json
{
  "id": "caea1345-da6a-4a1a-9047-96345307e010",
  "accountId": "5e824b14-77a3-4db7-882b-4c06abc2dc8b",
  "ownerUserId": "00000000-0000-0000-0000-000000000001",
  "idempotencyKey": "tx-example-001",
  "type": "DEPOSIT",
  "amountMinor": 1000,
  "currency": "USD",
  "status": "POSTED",
  "description": "Test deposit"
}
```

The transaction is returned as `POSTED` after the balanced ledger entries are written and the account balance is updated.

### `GET /transactions`

Required header:

```http
Authorization: Bearer <access_token>
```

Current successful response:

```json
[
  {
    "id": "caea1345-da6a-4a1a-9047-96345307e010",
    "accountId": "5e824b14-77a3-4db7-882b-4c06abc2dc8b",
    "ownerUserId": "00000000-0000-0000-0000-000000000001",
    "idempotencyKey": "tx-example-001",
    "type": "DEPOSIT",
    "amountMinor": 1000,
    "currency": "USD",
    "status": "POSTED",
    "description": "Test deposit"
  }
]
```

The endpoint lists transactions for the authenticated user only, newest first.

### `POST /transactions/{transactionId}/reverse`

Required headers:

```http
Authorization: Bearer <access_token>
Idempotency-Key: reverse-example-001
```

Request body:

```json
{
  "reason": "Customer requested reversal"
}
```

Current successful response:

```json
{
  "id": "9f582f2e-f2ca-4cc4-bec7-0a74ccf00cde",
  "accountId": "5e824b14-77a3-4db7-882b-4c06abc2dc8b",
  "ownerUserId": "00000000-0000-0000-0000-000000000001",
  "idempotencyKey": "reverse-example-001",
  "type": "WITHDRAWAL",
  "amountMinor": 1000,
  "currency": "USD",
  "status": "POSTED",
  "description": "Customer requested reversal",
  "reversalOfTransactionId": "caea1345-da6a-4a1a-9047-96345307e010"
}
```

The reversal is a new transaction. LedgerFlow does not delete the original transaction. The original row is marked with `reversed_at`, and the reversal row points back to it with `reversal_of_transaction_id`.

## 3. Submit Transaction Runtime Flow

```text
Client
  |
  | POST /transactions
  | Authorization: Bearer <access_token>
  | Idempotency-Key: tx-example-001
  v
Spring Security
  |
  +--> JwtAuthenticationFilter validates token
  +--> principal = user UUID from JWT sub claim
  |
  v
TransactionController
  |
  +--> ownerUserId = authentication.getPrincipal()
  +--> idempotencyKey = Idempotency-Key header
  |
  v
TransactionService
  |
  +--> normalize idempotency key
  +--> find existing transaction for owner + key
  |
  +--> if found, return existing transaction
  |
  +--> validate request
  +--> load account by accountId
  +--> verify account owner matches JWT user id
  +--> normalize currency
  +--> verify currency matches account currency
  +--> save PENDING transaction
  +--> calculate new balance
  +--> create user ledger entry
  +--> create settlement ledger entry
  +--> save updated account balance
  +--> update transaction to POSTED
  |
  v
PostgreSQL transactions, ledger_entries, and accounts tables
```

## 4. List Transactions Runtime Flow

```text
Client
  |
  | GET /transactions
  | Authorization: Bearer <access_token>
  v
Spring Security
  |
  +--> JwtAuthenticationFilter validates token
  +--> principal = user UUID from JWT sub claim
  |
  v
TransactionController
  |
  +--> ownerUserId = authentication.getPrincipal()
  |
  v
TransactionService
  |
  +--> find transactions for current owner
  +--> sort newest first through repository method name
  +--> convert Transaction rows to TransactionResponse
  |
  v
PostgreSQL transactions table
```

## 5. Reverse Transaction Runtime Flow

```text
Client
  |
  | POST /transactions/{transactionId}/reverse
  | Authorization: Bearer <access_token>
  | Idempotency-Key: reverse-example-001
  v
Spring Security
  |
  +--> JwtAuthenticationFilter validates token
  +--> principal = user UUID from JWT sub claim
  |
  v
TransactionController
  |
  +--> ownerUserId = authentication.getPrincipal()
  +--> transactionId = path variable
  +--> idempotencyKey = Idempotency-Key header
  +--> reason = request body
  |
  v
TransactionService
  |
  +--> normalize idempotency key
  +--> validate reversal reason
  +--> hash transactionId + reason
  +--> replay or reject duplicate idempotency key
  +--> load original transaction
  +--> verify original belongs to JWT user id
  +--> verify original is POSTED
  +--> verify original is not already reversed
  +--> load account
  +--> create opposite transaction type
  +--> create balanced ledger entries
  +--> update account balance
  +--> mark original transaction reversed_at
  +--> save idempotency record
  |
  v
PostgreSQL transactions, ledger_entries, accounts, and idempotency_keys tables
```

Reversal type mapping:

```text
Original DEPOSIT    -> reversal WITHDRAWAL
Original WITHDRAWAL -> reversal DEPOSIT
```

## 6. Concurrency Flow

LedgerFlow uses optimistic locking on account rows to protect balances under concurrent writes.

The account model has a Spring Data `@Version` field. When two requests read the same account version and both try to save updates, only the first save succeeds. The second save sees that the database version has changed and Spring throws an optimistic locking failure.

`TransactionService` catches that framework exception and converts it into a LedgerFlow domain/API error:

```text
409 CONCURRENT_TRANSACTION_CONFLICT
```

Example race:

```text
Account balance = 1000

Withdrawal A reads balance 1000, version 1
Withdrawal B reads balance 1000, version 1

Withdrawal A saves balance 300, version becomes 2
Withdrawal B tries to save balance 300 using old version 1
Withdrawal B gets optimistic locking conflict

Final balance = 300
```

The important outcome is that LedgerFlow does not allow both withdrawals to spend the same starting balance.

Current test coverage:

```text
TransactionConcurrencyTest.concurrentWithdrawalsDoNotOverdrawAccount
```

The test sends two simultaneous withdrawals of `700` against a `1000` balance. One request succeeds with `201`, the other returns `409`, and the final balance remains `300`.

## 7. Idempotency Flow

The current idempotency rule is:

```text
One idempotency key + one request hash = one transaction result
```

Database table:

```text
idempotency_keys
```

Important columns:

- `key`: idempotency key from the `Idempotency-Key` header.
- `owner_user_id`: authenticated user that created the key.
- `request_hash`: SHA-256 hash of the transaction request payload.
- `transaction_id`: transaction created by the first request.
- `response_status`: stored transaction response status such as `POSTED` or `FAILED`.
- `response_body`: stored JSON response metadata.
- `expires_at`: expiry timestamp after which the key is invalid.

Service behavior:

```text
First request
  |
  +--> no existing idempotency key row
  +--> create transaction A
  +--> save idempotency key + request hash + response metadata
  +--> create balanced ledger entries
  +--> update account balance

Second request with same key and same payload
  |
  +--> idempotency key exists
  +--> request hash matches
  +--> return transaction A
  +--> no additional ledger entries
  +--> no additional balance update

Second request with same key and different payload
  |
  +--> idempotency key exists
  +--> request hash differs
  +--> return 409 IDEMPOTENCY_CONFLICT
  +--> no transaction is created
  +--> no balance movement occurs
```

Current note: the controller still returns `201 Created` for both first and repeated idempotent submissions because it is annotated with `@ResponseStatus(HttpStatus.CREATED)`. The important behavior today is duplicate prevention, same-result return, and no duplicate balance movement.

## 8. Failed Transaction Flow

Insufficient funds is a business-rule failure that happens after the command is accepted and the target account is loaded.

Current behavior:

```text
POST /transactions WITHDRAWAL
  |
  +--> save PENDING transaction
  +--> calculate new balance
  +--> insufficient funds
  +--> update transaction to FAILED
  +--> return 422 INSUFFICIENT_FUNDS
```

The service uses:

```java
@Transactional(noRollbackFor = InsufficientFundsException.class)
```

That matters because `InsufficientFundsException` is a runtime exception. By default, Spring rolls back transactions for runtime exceptions. `noRollbackFor` lets LedgerFlow keep the `FAILED` transaction row while still returning a `422` response.

No ledger entries are created for the failed withdrawal, and the account balance is not changed.

## 9. Outbox Write Flow

When a transaction reaches `POSTED`, LedgerFlow writes a pending outbox event in the same PostgreSQL transaction as the transaction, ledger entries, and account balance update.

```text
TransactionService
  |
  +--> save transaction as POSTED
  +--> build TransactionPostedEventPayload
  +--> serialize payload to JSON
  +--> OutboxService.savePendingEvent(...)
  |
  v
outbox_events row with status PENDING
```

Current event metadata:

```text
aggregate_type = TRANSACTION
aggregate_id   = posted transaction id
event_type     = TRANSACTION_POSTED
status         = PENDING
payload        = transaction event JSON
```

This is the transactional outbox guarantee: if the database transaction commits, both the business state and the event-to-publish row commit together. The scheduled publisher later claims committed rows, sends the payload to Kafka, and marks each row as `PUBLISHED` or `FAILED`.

## 10. Database Design

### `transactions`

Created by:

```text
services/ledger-api/src/main/resources/db/migration/V4__create_transactions_table.sql
```

Important columns:

- `id`: transaction primary key.
- `account_id`: target account.
- `owner_user_id`: authenticated owner.
- `idempotency_key`: retry-safe request key.
- `type`: `DEPOSIT` or `WITHDRAWAL`.
- `amount_minor`: positive minor-unit amount.
- `currency`: three-letter uppercase currency code.
- `status`: `PENDING`, `POSTED`, or `FAILED`.
- `description`: optional user-facing description.
- `reversal_of_transaction_id`: original transaction id when this row is a reversal.
- `reversed_at`: timestamp showing when the original transaction was reversed.
- `version`: optimistic concurrency field.
- `created_at`, `updated_at`: timestamps.

Important indexes:

- `idx_transactions_account_id`
- `idx_transactions_owner_created_at`
- `idx_transactions_reversal_of_transaction_id`

The owner/time index supports listing a user's transactions newest-first.

### `idempotency_keys`

Created by:

```text
services/ledger-api/src/main/resources/db/migration/V7__create_idempotency_keys_table.sql
```

Important columns:

- `key`: idempotency key primary key.
- `owner_user_id`: authenticated user that created the idempotency record.
- `request_hash`: SHA-256 hash of the request payload.
- `transaction_id`: transaction associated with the first accepted request.
- `response_status`: stored response status metadata.
- `response_body`: stored JSON response metadata.
- `expires_at`: key expiry timestamp.

This table closes the important idempotency gap: a duplicate key with a different payload is no longer treated as a retry.

### `ledger_entries`

Created by:

```text
services/ledger-api/src/main/resources/db/migration/V5__create_ledger_entries_table.sql
```

Important columns:

- `id`: ledger entry primary key.
- `transaction_id`: transaction that produced the entry.
- `account_id`: account affected by the entry.
- `direction`: `DEBIT` or `CREDIT`.
- `amount_minor`: positive minor-unit amount.
- `currency`: three-letter uppercase currency code.
- `created_at`: timestamp.
- `version`: optimistic concurrency field.

Important indexes:

- `idx_ledger_entries_transaction_id`
- `idx_ledger_entries_account_created_at`

The current service writes two ledger entries for each newly posted transaction:

- user account entry
- USD settlement system account entry

The debit total and credit total for each transaction should match.

### System Settlement Account

Seeded by:

```text
services/ledger-api/src/main/resources/db/migration/V6__seed_system_account.sql
```

Referenced in Java through:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/ledger/SystemAccounts.java
```

Current constant:

```java
SystemAccounts.USD_SETTLEMENT_ACCOUNT_ID
```

### `outbox_events`

Created by:

```text
services/ledger-api/src/main/resources/db/migration/V9__create_outbox_events_table.sql
```

Important columns:

- `id`: outbox event primary key.
- `aggregate_type`: domain aggregate category, currently `TRANSACTION`.
- `aggregate_id`: aggregate id, currently the posted transaction id.
- `event_type`: event name, currently `TRANSACTION_POSTED`.
- `payload`: JSONB event payload.
- `status`: `PENDING`, `PROCESSING`, `PUBLISHED`, or `FAILED`.
- `attempts`: publish attempt count.
- `next_attempt_at`: earliest retry time.
- `claimed_by`: publisher instance that claimed the row.
- `locked_until`: claim expiry timestamp for crash recovery.
- `published_at`: timestamp set after successful publishing.
- `last_error`: latest publisher error.
- `created_at`, `updated_at`: timestamps.

Important indexes:

- `idx_outbox_events_publishable`
- `idx_outbox_events_stale_claims`
- `idx_outbox_events_aggregate`

The split publishable/stale-claim indexes reflect two different publisher queries: normal queue polling and crashed-claim recovery.

### `consumed_ledger_events`

Created by:

```text
services/ledger-api/src/main/resources/db/migration/V10__create_consumed_ledger_events_table.sql
```

Important columns:

- `id`: consumed-event audit row primary key.
- `event_type`: event name, currently `TRANSACTION_POSTED`.
- `transaction_id`: transaction id parsed from the consumed event payload.
- `payload`: original consumed event payload stored as JSONB.
- `consumed_at`: timestamp when the consumer recorded the event.

Important constraint:

```text
UNIQUE (transaction_id, event_type)
```

That uniqueness makes duplicate Kafka delivery safe for the current consumer side effect. If Kafka redelivers the same `TRANSACTION_POSTED` event, the insert uses `ON CONFLICT DO NOTHING` and the table still contains only one audit row.

### `reconciliation_reports`

Created by:

```text
services/ledger-api/src/main/resources/db/migration/V11__create_reconciliation_reports_table.sql
```

Important columns:

- `id`: report primary key.
- `report_type`: report category, currently `LEDGER_BALANCE_CHECK`.
- `status`: `PASSED` or `FAILED`.
- `checked_transactions`: number of posted transactions included in the check.
- `imbalance_count`: number of transactions whose ledger entries do not balance.
- `details`: JSONB report metadata.
- `started_at`, `completed_at`: reconciliation timing.

The current report checks the double-entry invariant:

```text
for each posted transaction:
  total DEBIT amount - total CREDIT amount == 0
```

## 11. Outbox Publisher Flow

The scheduled outbox publisher turns durable database events into Kafka messages.

```text
OutboxPublisherScheduler
  |
  | every ledgerflow.outbox.publisher.fixed-delay-ms
  v
OutboxPublisherService.publishBatch()
  |
  +--> OutboxEventRepository.claimPublishableEvents(...)
  |       |
  |       +--> PENDING / retryable FAILED rows
  |       +--> stale PROCESSING rows where locked_until < now()
  |       +--> FOR UPDATE SKIP LOCKED
  |       +--> status = PROCESSING
  |
  +--> KafkaTemplate.send("ledger.events", aggregateId, payload)
  |
  +--> success: markPublished(...)
  |       |
  |       +--> status = PUBLISHED
  |       +--> published_at = now()
  |       +--> claimed_by / locked_until cleared
  |
  +--> failure: markFailed(...)
          |
          +--> status = FAILED
          +--> attempts incremented
          +--> next_attempt_at scheduled
          +--> last_error stored
          +--> claimed_by / locked_until cleared
```

The publisher is controlled by:

```yaml
ledgerflow:
  outbox:
    publisher:
      enabled: true
      fixed-delay-ms: 5000
```

Tests disable the scheduler in `src/test/resources/application.yml` so Kafka publishing does not run in the background while MockMvc tests are executing.

## 12. Kafka Consumer Flow

The current consumer records an idempotent audit row for consumed ledger events. It is still intentionally small: it proves that the application can receive events from Kafka and perform a retry-safe database side effect without adding projection or reconciliation logic yet.

```text
Kafka topic: ledger.events
  |
  v
LedgerEventsConsumer.consume(payload)
  |
  +--> parse transactionId from JSON
  +--> eventType = TRANSACTION_POSTED
  +--> ConsumedLedgerEventRepository.insertIfNotExists(...)
  |
  v
consumed_ledger_events row
```

Current behavior:

- consumes string payloads from `ledger.events`
- parses `transactionId` from the raw `TRANSACTION_POSTED` JSON payload
- inserts one row into `consumed_ledger_events`
- ignores duplicate `(transaction_id, event_type)` deliveries
- does not write projections
- does not update reconciliation state
- does not write dead-letter records

Tests disable listener startup with:

```yaml
spring:
  kafka:
    listener:
      auto-startup: false
```

That keeps MockMvc tests from opening background Kafka listener connections.

## 13. Reconciliation Flow

The current reconciliation slice validates the ledger balance invariant and stores an audit report.

```text
Client
  |
  | POST /reconciliation/ledger-balance-check
  | Authorization: Bearer <access_token>
  v
ReconciliationController
  |
  v
ReconciliationService.runLedgerBalanceCheck()
  |
  +--> count POSTED transactions
  +--> count transactions whose ledger entries do not balance
  +--> status = PASSED if imbalanceCount == 0 else FAILED
  +--> save ReconciliationReport
  |
  v
reconciliation_reports row
```

Manual verification passed with:

```text
checkedTransactions = 1006
imbalanceCount      = 0
status              = PASSED
```

This proves the currently-posted local ledger entries satisfy the double-entry balance invariant.

## 14. File-by-File Explanation

### `TransactionController.java`

Defines:

```text
POST /transactions
GET  /transactions
POST /transactions/{transactionId}/reverse
```

For submission, it reads:

- authenticated user id from `Authentication`
- idempotency key from the `Idempotency-Key` header
- transaction fields from `CreateTransactionRequest`

For listing, it reads the authenticated user id from `Authentication`.

For reversals, it reads:

- authenticated user id from `Authentication`
- original transaction id from the path
- idempotency key from the `Idempotency-Key` header
- reversal reason from `ReverseTransactionRequest`

All methods delegate to `TransactionService`.

### `TransactionService.java`

Contains transaction command business logic:

- normalize and validate idempotency key
- return existing transaction for repeated owner/key pair
- list transactions by authenticated owner
- reverse posted transactions through offsetting transactions
- validate request body
- load account
- enforce account ownership
- enforce active account status
- enforce currency match
- save `PENDING` transaction
- calculate the new account balance
- update transaction to `FAILED` when insufficient funds occurs
- create user ledger entry
- create settlement ledger entry
- save the updated account
- update the transaction to `POSTED`
- mark original transactions with `reversed_at`
- save idempotency records for transaction submissions and reversals
- write `TRANSACTION_POSTED` outbox events for posted transactions and reversals

It is wrapped in `@Transactional(noRollbackFor = InsufficientFundsException.class)`.

Successful posting commits transaction creation, ledger entry creation, account balance update, and status update together. Insufficient funds still throws an API error, but the `FAILED` transaction row is committed.

Reversal posting commits the reversal transaction, balanced ledger entries, account balance update, original transaction `reversed_at`, idempotency record, and outbox event together.

### `TransactionRepository.java`

Extends:

```java
CrudRepository<Transaction, UUID>
```

Important derived queries:

- `findByOwnerUserIdOrderByCreatedAtDesc(...)`
- `findByAccountIdOrderByCreatedAtDesc(...)`

Idempotency decisions happen through `IdempotencyRepository`, not through a transaction-table lookup.

### `IdempotencyRepository.java`

Uses `NamedParameterJdbcTemplate` for the `idempotency_keys` table.

It can:

- find an idempotency record by key
- save the request hash, transaction id, response status, response body, and expiry

This repository uses explicit SQL because `response_body` is a PostgreSQL `jsonb` column.

### `OutboxService.java`

Creates pending outbox events for committed domain changes.

Current transaction usage:

```text
aggregateType = TRANSACTION
eventType     = TRANSACTION_POSTED
status        = PENDING
```

The service builds the `OutboxEvent`; the repository owns the database-specific insert.

### `OutboxEventRepository.java`

Uses `NamedParameterJdbcTemplate` for the `outbox_events` table.

It uses explicit SQL because:

- `payload` is PostgreSQL `jsonb`, so writes cast `:payload` with `CAST(:payload AS jsonb)`.
- outbox publisher work will need claim/retry queries that are more precise than simple derived repository methods.

Current methods:

- `save(...)`
- `findByAggregateId(...)`
- `claimPublishableEvents(...)`
- `markPublished(...)`
- `markFailed(...)`

`claimPublishableEvents(...)` uses `FOR UPDATE SKIP LOCKED` so multiple publisher instances can safely claim different rows without blocking each other.

### `OutboxPublisherService.java`

Claims publishable outbox events, sends each payload to Kafka topic `ledger.events`, and updates the row based on the outcome.

Successful sends call `markPublished(...)`. Failed sends call `markFailed(...)` and schedule the next retry.

### `OutboxPublisherScheduler.java`

Runs `OutboxPublisherService.publishBatch()` on a fixed delay.

The scheduler can be disabled with:

```text
ledgerflow.outbox.publisher.enabled=false
```

### `LedgerEventsConsumer.java`

Listens to Kafka topic `ledger.events`.

Current behavior:

```text
payload -> parse transactionId -> insert consumed_ledger_events
```

Duplicate consumed events are ignored by the repository through `ON CONFLICT DO NOTHING`.

This confirms end-to-end publish/consume wiring plus a small idempotent consumer side effect. It is not yet a projection, reconciliation, or dead-letter implementation.

### `ConsumedLedgerEvent.java`

Data carrier for one consumed event audit row.

### `ConsumedLedgerEventRepository.java`

Uses `NamedParameterJdbcTemplate` for the `consumed_ledger_events` table.

It uses explicit SQL because:

- `payload` is PostgreSQL `jsonb`, so writes cast `:payload` with `CAST(:payload AS jsonb)`.
- duplicate delivery is handled atomically with `ON CONFLICT (transaction_id, event_type) DO NOTHING`.

Current methods:

- `insertIfNotExists(...)`
- `countByTransactionIdAndEventType(...)`

### `PayCoreConsumer.java`

Listens to upstream PayCore Kafka topics:

```text
payment.captured
payment.settled
```

Current behavior:

```text
PayCore event payload
  -> parse + validate
  -> eventId as idempotency key
  -> TransactionService.submitTransaction(...)
  -> LedgerFlow DEPOSIT transaction
```

The consumer uses the existing transaction service instead of writing ledger rows directly. That keeps PayCore ingestion on the same path as HTTP transaction submission: ownership checks, account-state checks, currency validation, idempotency, optimistic locking, ledger entries, balance updates, and outbox event creation all remain centralized.

Invalid PayCore events are written to `dead_letter_events` with status `PENDING`.

### `PayCorePaymentCapturedPayload.java`

Record for `payment.captured` events.

### `PayCorePaymentSettledPayload.java`

Record for `payment.settled` events.

### `DeadLetterEventRepository.java`

Uses `NamedParameterJdbcTemplate` for `dead_letter_events`.

Current methods:

- `save(...)`
- `findPending(...)`
- `findById(...)`
- `markReplayed(...)`

Payloads are stored as PostgreSQL `jsonb`. Replay state is tracked through `status`, `attempts`, and `replayed_at`.

### `DeadLetterReplayService.java`

Replays pending dead-letter rows by routing them back through the matching consumer method.

Current routing:

```text
payment.captured -> PayCoreConsumer.consumePaymentCaptured(...)
payment.settled  -> PayCoreConsumer.consumePaymentSettled(...)
```

After replay succeeds, the row is marked `REPLAYED`.

### `DeadLetterController.java`

Defines:

```text
POST /admin/dead-letter/replay?limit=10
```

The endpoint is authenticated by default.

### `ReconciliationController.java`

Defines:

```text
POST /reconciliation/ledger-balance-check
```

The endpoint is authenticated by default through Spring Security.

### `ReconciliationService.java`

Runs the current ledger balance check:

- counts posted transactions
- counts imbalanced transaction ledgers
- creates a `PASSED` or `FAILED` report
- persists the report

### `ReconciliationReportRepository.java`

Uses `NamedParameterJdbcTemplate` for `reconciliation_reports`.

It owns explicit SQL because:

- `details` is PostgreSQL `jsonb`
- reconciliation queries are report-specific SQL

Current methods:

- `save(...)`
- `countById(...)`
- `countLedgerEntryImbalances(...)`
- `countPostedTransactions(...)`

### `ReconciliationReport.java`

Data carrier for a reconciliation report row.

### `ReconciliationReportStatus.java`

Enum:

- `PASSED`
- `FAILED`

### `OutboxEvent.java`

Data carrier for one row in `outbox_events`.

### `OutboxEventStatus.java`

Enum:

- `PENDING`
- `PROCESSING`
- `PUBLISHED`
- `FAILED`

### `TransactionPostedEventPayload.java`

Event payload DTO for `TRANSACTION_POSTED`.

This is separate from `TransactionResponse` because API responses and asynchronous event contracts can evolve differently.

### `IdempotencyRecord.java`

Small data carrier for one row in `idempotency_keys`.

### `IdempotencyConflictException.java`

Thrown when a duplicate idempotency key is reused with a different request payload.

Mapped to:

```text
409 IDEMPOTENCY_CONFLICT
```

### `ExpiredIdempotencyKeyException.java`

Thrown when a request tries to reuse an expired idempotency key.

Mapped to:

```text
400 EXPIRED_IDEMPOTENCY_KEY
```

### `Transaction.java`

Spring Data JDBC model for the `transactions` table.

Important annotations:

- `@Table("transactions")`
- `@Id`
- `@Version`

`@Version` matters because transactions use Java-generated UUIDs. It also prepares the model for optimistic concurrency.

Reversal fields:

- `reversalOfTransactionId`: set on the reversal transaction to point back to the original.
- `reversedAt`: set on the original transaction after a successful reversal.

### `CreateTransactionRequest.java`

Request DTO for `POST /transactions`.

Fields:

- `accountId`
- `type`
- `amountMinor`
- `currency`
- `description`

The idempotency key is intentionally not in this DTO. It comes from the HTTP `Idempotency-Key` header.

### `ReverseTransactionRequest.java`

Request DTO for `POST /transactions/{transactionId}/reverse`.

Fields:

- `reason`

The transaction id comes from the URL path, and the idempotency key comes from the HTTP `Idempotency-Key` header.

### `TransactionResponse.java`

Response DTO for transaction submission.

Fields:

- `id`
- `accountId`
- `ownerUserId`
- `idempotencyKey`
- `type`
- `amountMinor`
- `currency`
- `status`
- `description`
- `reversalOfTransactionId`

### `TransactionType.java`

Enum:

- `DEPOSIT`
- `WITHDRAWAL`

### `TransactionStatus.java`

Enum:

- `PENDING`
- `POSTED`
- `FAILED`

Successful submissions currently return `POSTED`. The service still creates a `PENDING` transaction first, then updates it to `POSTED` inside the same database transaction.

Insufficient withdrawals are saved as `FAILED` and returned through `GET /transactions`.

### `InvalidTransactionRequestException.java`

Thrown for invalid transaction input.

Examples:

- missing idempotency key
- idempotency key too long
- missing account id
- missing transaction type
- non-positive amount
- invalid currency
- currency mismatch

Mapped to:

```text
400 INVALID_TRANSACTION_REQUEST for malformed transaction input
422 CURRENCY_MISMATCH for account/transaction currency mismatch
```

### `InvalidReversalRequestException.java`

Thrown for invalid reversal input or invalid reversal state.

Examples:

- missing reversal reason
- original transaction not found
- original transaction is not `POSTED`
- original transaction was already reversed

Mapped to:

```text
400 INVALID_REVERSAL_REQUEST
```

### `AccountNotFoundException.java`

Path:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/account/AccountNotFoundException.java
```

Thrown when the submitted account id does not exist.

Mapped to:

```text
404 ACCOUNT_NOT_FOUND
```

### `AccountOwnershipException.java`

Path:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/account/AccountOwnershipException.java
```

Thrown when the target account exists but does not belong to the authenticated user.

Mapped to:

```text
403 ACCOUNT_FORBIDDEN
```

### `LedgerEntry.java`

Spring Data JDBC model for the `ledger_entries` table.

`TransactionService` writes this model when a new transaction is posted.

### `LedgerEntryRepository.java`

Repository for ledger entry lookups.

Important derived queries:

- `findByTransactionId(...)`
- `findByAccountIdOrderByCreatedAtDesc(...)`

### `LedgerEntryDirection.java`

Enum:

- `DEBIT`
- `CREDIT`

### `SystemAccounts.java`

Defines known system account ids used by ledger posting.

Current constant:

```java
USD_SETTLEMENT_ACCOUNT_ID
```

This points to the seeded USD settlement account used for offset entries.

## 15. Design Decisions

### Why Idempotency Uses a Header

Idempotency is request metadata, not transaction business content. This mirrors payment-style APIs where clients retry the same logical request with the same key.

### Why Idempotency Uses Request Hashes

Idempotency should only replay the original result when the retry is the same logical request.

If the same key is reused with a different amount, account, type, currency, or description, LedgerFlow returns:

```text
409 IDEMPOTENCY_CONFLICT
```

This prevents a client from accidentally or maliciously reusing an old key for a different transaction.

### Why Idempotency Stores Response Metadata

The `idempotency_keys` table stores response metadata so the system can explain what the first request produced.

Current retry behavior reloads the associated transaction and returns the same transaction result. The stored response body prepares the project for exact HTTP response replay as the API contract becomes stricter.

### Why Transaction Listing Is Scoped By Owner

`GET /transactions` uses the authenticated user id from the JWT, not a user id from the request.

This keeps users from listing another user's transaction history.

The repository method is:

```java
findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId)
```

The method name expresses both the ownership filter and newest-first ordering.

### Why Account Ownership Is Checked In The Service

The request includes `accountId`, but the user identity comes from the JWT.

The service loads the account and verifies:

```text
account.ownerUserId == authenticated user id
```

This prevents users from posting transactions to accounts they do not own.

### Why Transactions Are Saved As `PENDING` First

The service first records the accepted command as `PENDING`, then creates the balanced ledger entries, updates the balance, and saves the transaction as `POSTED`.

Because the method is `@Transactional`, these steps are committed together.

### Why Insufficient Funds Records `FAILED`

An insufficient withdrawal is still a transaction command the API accepted and evaluated.

Recording it as `FAILED` gives LedgerFlow an audit trail:

```text
user tried withdrawal -> system rejected it -> reason was insufficient funds
```

The current API response remains:

```text
422 INSUFFICIENT_FUNDS
```

The failed row is visible later through:

```text
GET /transactions
```

### Why `noRollbackFor` Is Used

Spring normally rolls back a transaction when a `RuntimeException` is thrown.

`InsufficientFundsException` extends `RuntimeException`, but LedgerFlow wants the `FAILED` transaction row to commit. That is why `submitTransaction(...)` uses:

```java
@Transactional(noRollbackFor = InsufficientFundsException.class)
```

This keeps the audit row while still letting the controller return an error response.

### Why A Settlement Account Exists

Double-entry needs both sides of a movement. LedgerFlow uses a seeded USD settlement system account as the offset side for the current implementation.

Deposit:

```text
user account            CREDIT
USD settlement account  DEBIT
```

Withdrawal:

```text
user account            DEBIT
USD settlement account  CREDIT
```

This keeps each transaction balanced:

```text
total debits == total credits
```

Richer system-account modeling is still future work.

### Ledger Directions Today

- `DEPOSIT` -> `CREDIT`
- `WITHDRAWAL` -> `DEBIT`

Those directions describe the user account entry. The settlement account receives the opposite direction.

### Why Reversal Creates A New Transaction

LedgerFlow reverses by adding a new offsetting transaction instead of deleting or mutating the original movement.

That preserves the audit trail:

```text
original transaction happened
reversal transaction happened later
original transaction now has reversed_at
reversal transaction points to original through reversal_of_transaction_id
```

This is closer to financial systems than deleting rows, because history remains explainable.

### Why Reversal Uses Idempotency Too

Reversal is also a money-moving command. If a client retries the same reversal request after a timeout, LedgerFlow must return the original reversal result instead of changing the balance twice.

The reversal request hash uses:

```text
original transaction id + reversal reason
```

The same idempotency conflict rule applies:

```text
same key + same reversal payload      -> replay original reversal
same key + different reversal payload -> 409 IDEMPOTENCY_CONFLICT
```

### Why Outbox Writes Happen In The Same Transaction

The outbox row is the durable promise that a committed business change will eventually be published to Kafka.

If LedgerFlow updated balances and then tried to publish directly to Kafka, it could crash between the database commit and Kafka publish. That would create a dual-write inconsistency.

Instead, transaction posting writes:

```text
transaction row
ledger entries
account balance update
outbox event row
```

inside the same PostgreSQL transaction.

The scheduled publisher safely reads committed `PENDING` rows and publishes them after the business transaction commits.

### Why Outbox Uses Explicit SQL

The outbox payload is PostgreSQL `jsonb`. Spring Data JDBC does not automatically know how to bind a Java `String` as JSONB or read PostgreSQL `PGobject` back into a string without converters.

The repository uses explicit SQL so the boundary is obvious:

```sql
CAST(:payload AS jsonb)
```

The repository also owns claim-based publisher queries such as `FOR UPDATE SKIP LOCKED`, `markPublished(...)`, and `markFailed(...)`.

## 16. Common Debugging Lessons

### Reused Idempotency Keys Return Old Results

If a test or curl request reuses the same `Idempotency-Key` with the same payload, the API should return the existing transaction.

That is correct behavior. Use a unique key when testing new transaction creation.

Idempotent retries must also avoid duplicate balance updates. The tests verify this.

### Same Idempotency Key With Different Payload Returns 409

If the request hash differs from the stored hash, LedgerFlow returns:

```text
409 IDEMPOTENCY_CONFLICT
```

That means the request is not a retry. It is a different command using a previously used key.

### Java-Generated UUIDs Need `@Version`

Without `@Version`, Spring Data JDBC may treat an object with a non-null UUID as an existing row and try to update instead of insert.

This was fixed by adding:

```java
@Version long version
```

to `Transaction`.

### Protected Endpoints Return `401` Without A Valid JWT

Protected endpoints return `401 UNAUTHORIZED` when the JWT is missing or invalid.

`403 ACCOUNT_FORBIDDEN` is reserved for authenticated users trying to access an account they do not own.

### `FAILED` Row Disappears After Throwing

If a service saves a `FAILED` row and then throws a runtime exception, Spring may roll back the save.

For insufficient funds, LedgerFlow avoids that with:

```java
@Transactional(noRollbackFor = InsufficientFundsException.class)
```

### Reversal Retry Returns The Existing Reversal

If a reversal request times out client-side, retry the exact same request with the same `Idempotency-Key`.

Expected behavior:

```text
same reversal id
same reversalOfTransactionId
no additional balance change
```

If the reason or transaction id changes while reusing the same key, the API returns `409 IDEMPOTENCY_CONFLICT`.

### Concurrent Withdrawals Return 409 For The Losing Request

If two withdrawals race against the same account balance, one may successfully commit first. The other may fail because its in-memory account version is stale.

LedgerFlow maps that stale-version failure to:

```text
409 CONCURRENT_TRANSACTION_CONFLICT
```

That response means the client should reload state and decide whether to retry. It is different from `422 INSUFFICIENT_FUNDS`: insufficient funds is a business-rule failure based on the current balance, while `409` is a write conflict caused by concurrent modification.

## 17. Interview Questions and Answers

1. **What does the transaction endpoint currently do?**  
   It accepts an authenticated transaction command, creates balanced ledger entries, updates the account balance, and returns a `POSTED` transaction.

2. **Does it update account balances today?**  
   Yes. Deposits increase balance and withdrawals decrease balance.

3. **Does it create ledger entries today?**  
   Yes. It creates two balanced ledger entries per posted transaction.

4. **Why use an `Idempotency-Key` header?**  
   It lets clients safely retry the same request without creating duplicate transactions.

5. **How is idempotency scoped?**  
   By the idempotency key and stored request hash in `idempotency_keys`.

6. **What happens when the same user sends the same idempotency key again?**  
   If the request hash matches, the service returns the existing transaction instead of creating a new row.

7. **What happens when the same idempotency key is reused with a different payload?**  
   The API returns `409 IDEMPOTENCY_CONFLICT` and creates no transaction or balance movement.

8. **Why does the transaction table include `owner_user_id`?**  
   It scopes idempotency and supports user-specific transaction queries.

9. **Why check account ownership?**  
   A user must not submit transactions against another user's account.

10. **Why validate currency against the account?**  
   A transaction should not post `SGD` against a `USD` account.

11. **Why are amounts stored as `amount_minor`?**  
    Minor-unit integers avoid floating point money errors.

12. **Why does `Transaction` have `@Version`?**  
    It helps Spring Data JDBC insert Java-generated UUID rows correctly and prepares for optimistic concurrency.

13. **Why does the service save a `PENDING` transaction first?**  
    It records the command before posting, then updates the transaction to `POSTED` inside the same database transaction.

14. **What makes a transaction `POSTED` today?**  
    Successful ledger entry creation and account balance update.

15. **What makes a transaction `FAILED` today?**  
    An insufficient-funds withdrawal creates a `FAILED` transaction row and returns `422 INSUFFICIENT_FUNDS`.

16. **Why separate `transactions` from `ledger_entries`?**  
    Transactions are user commands; ledger entries are accounting facts.

17. **What happens on insufficient funds?**  
    The service saves the transaction as `FAILED`, throws `InsufficientFundsException`, and the API returns `422 INSUFFICIENT_FUNDS`.

18. **How does double-entry work here today?**  
    Deposits credit the user account and debit the USD settlement account. Withdrawals debit the user account and credit the USD settlement account.

19. **What does the balanced ledger test prove?**  
    For a transaction, total debits equal total credits.

20. **What does `GET /transactions` return?**  
    It returns the authenticated user's transactions, newest first.

21. **Why does `GET /transactions` not accept a user id parameter?**  
    The user id comes from the validated JWT so callers cannot request another user's transaction history.

22. **Why does `submitTransaction` use `noRollbackFor`?**  
    Without it, Spring would roll back the saved `FAILED` row when `InsufficientFundsException` is thrown.

23. **How does LedgerFlow reverse a transaction?**  
    It creates a new offsetting `POSTED` transaction, writes balanced ledger entries, updates the account balance, and marks the original transaction with `reversed_at`.

24. **Why not delete the original transaction during reversal?**  
    Financial history must remain auditable. The original transaction and the reversal both stay visible.

25. **How does reversal idempotency work?**  
    The reversal endpoint uses the same `idempotency_keys` table. Same key and same payload replay the reversal; same key with different payload returns `409`.

26. **What prevents double reversal?**  
    The service rejects reversal when the original transaction already has `reversed_at`.

27. **How does LedgerFlow prevent concurrent withdrawals from overdrawing an account?**  
    Account rows use optimistic locking. If two requests race using the same account version, one update wins and the stale update returns `409 CONCURRENT_TRANSACTION_CONFLICT`.

28. **Why return 409 for an optimistic locking conflict?**  
    It is a state conflict, not malformed input. The client should reload the account state before retrying.

29. **What outbox event is written today?**  
    `TRANSACTION_POSTED` with aggregate type `TRANSACTION` and aggregate id equal to the posted transaction id.

30. **Why write the outbox row in the same transaction as ledger posting?**  
    It prevents dual-write inconsistency. If the business transaction commits, the durable event row commits too.

31. **Why is the outbox payload stored as JSONB?**  
    It keeps the event payload structured and queryable while still allowing flexible event shapes.

32. **Why does `OutboxEventRepository` use explicit SQL?**  
    JSONB binding and claim-based polling are database-specific enough that explicit JDBC is clearer than derived CRUD methods.

33. **How does the outbox publisher avoid multiple instances publishing the same row?**  
    It claims rows with `FOR UPDATE SKIP LOCKED`, so concurrent publisher instances skip rows already locked by another transaction.

34. **Why does the outbox table use `locked_until`?**  
    It gives claims an expiry time. If a publisher crashes while processing a row, a later run can reclaim it after the lock expires.

35. **What happens after Kafka publish succeeds?**  
    The publisher marks the event `PUBLISHED`, sets `published_at`, and clears claim fields.

36. **What happens after Kafka publish fails?**  
    The publisher marks the event `FAILED`, increments `attempts`, stores `last_error`, and schedules `next_attempt_at`.

37. **What does the current Kafka consumer do?**  
    It consumes payloads from `ledger.events`, parses `transactionId`, and records an idempotent audit row in `consumed_ledger_events`.

38. **How does the consumer handle duplicate Kafka delivery?**  
    `ConsumedLedgerEventRepository` inserts with `ON CONFLICT (transaction_id, event_type) DO NOTHING`, so repeated delivery does not create duplicate audit rows.

39. **Why keep the first consumer side effect small?**  
    It proves end-to-end Kafka wiring and retry-safe consumption before adding projections, reconciliation state, or dead-letter handling.

40. **What does the current reconciliation check verify?**  
    It verifies that each posted transaction has balanced ledger entries: total debits equal total credits.

41. **What does `imbalanceCount = 0` mean?**  
    It means no posted transaction violated the double-entry balance invariant during that reconciliation run.

42. **Why persist reconciliation reports?**  
    They provide an audit trail of when checks ran, what was checked, and whether the ledger passed.

43. **How does PayCore ingestion avoid duplicate balance movement?**  
    The consumer uses PayCore `eventId` as the LedgerFlow idempotency key, so redelivered events replay the existing transaction instead of posting again.

44. **Why does `PayCoreConsumer` call `TransactionService`?**  
    The transaction service already owns validation, idempotency, ledger posting, balance updates, optimistic locking, and outbox writes.

45. **What PayCore topics are consumed today?**  
    `payment.captured` and `payment.settled`.

46. **What happens when PayCore ingestion receives an invalid event?**  
    The raw payload and error message are stored in `dead_letter_events` with status `PENDING`.

47. **How does dead-letter replay work today?**  
    `POST /admin/dead-letter/replay` loads pending rows and routes each row back through the matching PayCore consumer method.

48. **Why mark a dead-letter row as `REPLAYED`?**  
    It creates an audit trail showing that the failed event was explicitly retried and prevents the same row from remaining in the pending replay queue.

## 18. Checklist Before Moving On

Before moving on to richer reconciliation details and dead-letter handling, be able to explain:

- [ ] Why `POST /transactions` requires authentication.
- [ ] Why `GET /transactions` is scoped to the authenticated user.
- [ ] Why idempotency key lives in a header.
- [ ] How repeated idempotency keys return the same transaction.
- [ ] How request hashes detect idempotency key misuse.
- [ ] Why different payloads with the same idempotency key return `409`.
- [ ] Why account ownership is checked after loading the account.
- [ ] Why transaction currency must match account currency.
- [ ] Why transactions are saved as `PENDING` first and then updated to `POSTED`.
- [ ] Why insufficient funds saves a `FAILED` transaction row.
- [ ] Why `noRollbackFor = InsufficientFundsException.class` is needed.
- [ ] What the `transactions` table stores.
- [ ] What the `ledger_entries` table stores.
- [ ] Why the USD settlement system account exists.
- [ ] How deposit ledger directions differ from withdrawal directions.
- [ ] Why `Transaction` has `@Version`.
- [ ] What `TransactionFlowTest` proves.
- [ ] How idempotent retries avoid double balance updates.
- [ ] How balanced ledger entries preserve `total debits == total credits`.
- [ ] Why reversal creates a new offsetting transaction.
- [ ] How `reversalOfTransactionId` and `reversedAt` differ.
- [ ] Why reversal also needs idempotency.
- [ ] Why a double reversal is rejected.
- [ ] How optimistic locking protects account balance updates.
- [ ] Why simultaneous withdrawals can produce one `201` and one `409`.
- [ ] Why `409 CONCURRENT_TRANSACTION_CONFLICT` is different from `422 INSUFFICIENT_FUNDS`.
- [ ] Why outbox rows are written in the same database transaction as business writes.
- [ ] What `TRANSACTION_POSTED` contains today.
- [ ] Why `OutboxEventRepository` uses explicit SQL for JSONB.
- [ ] How `FOR UPDATE SKIP LOCKED` supports concurrent outbox publishers.
- [ ] Why `locked_until` supports stale claim recovery.
- [ ] How successful publishes become `PUBLISHED`.
- [ ] How failed publishes become `FAILED` and retryable.
- [ ] Why the test profile disables the scheduled publisher.
- [ ] What `LedgerEventsConsumer` consumes today.
- [ ] What `consumed_ledger_events` stores.
- [ ] Why duplicate consumed events are ignored.
- [ ] Why the first consumer side effect is intentionally small.
- [ ] Why tests disable Kafka listener auto-startup.
- [ ] What `POST /reconciliation/ledger-balance-check` does.
- [ ] How `imbalanceCount` is calculated.
- [ ] Why reconciliation reports are persisted.
- [ ] What the current reconciliation slice does not check yet.
- [ ] How PayCore `payment.captured` and `payment.settled` become LedgerFlow transactions.
- [ ] Why PayCore `eventId` is used as the idempotency key.
- [ ] Why PayCore ingestion reuses `TransactionService`.
- [ ] How invalid PayCore events are stored in `dead_letter_events`.
- [ ] How `POST /admin/dead-letter/replay` routes pending rows.
- [ ] Why replay marks rows as `REPLAYED`.
- [ ] What the Testcontainers PostgreSQL migration test proves.
- [ ] What the Testcontainers Kafka outbox tests prove.
- [ ] What the Testcontainers PayCore consumer tests prove.
