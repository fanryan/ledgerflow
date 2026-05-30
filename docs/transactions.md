# Transactions

This document explains the current LedgerFlow transaction posting foundation. It focuses on what exists today: accepting authenticated transaction commands, validating ownership and currency, enforcing idempotency, creating balanced ledger entries, updating account balances, returning `POSTED` transaction rows, and recording `FAILED` rows for insufficient funds.

The current implementation creates two balanced ledger entries per posted transaction: one for the user account and one for the seeded USD settlement system account.

## 1. Current Transaction Scope

### Implemented

The Spring Boot API currently supports:

- `transactions` table through Flyway migration `V4__create_transactions_table.sql`.
- `ledger_entries` table through Flyway migration `V5__create_ledger_entries_table.sql`.
- `idempotency_keys` table through Flyway migration `V7__create_idempotency_keys_table.sql`.
- Authenticated `POST /transactions`.
- Authenticated `GET /transactions`.
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
- Insufficient withdrawals return `422 INSUFFICIENT_FUNDS`.
- Insufficient withdrawals are recorded as `FAILED` transactions.
- Balanced ledger entries are written for deposits and withdrawals.
- USD settlement system account seeded by `V6__seed_system_account.sql`.
- `SystemAccounts.USD_SETTLEMENT_ACCOUNT_ID` centralizes the settlement account UUID in Java.
- Java models and repositories for `Transaction` and `LedgerEntry`.
- Transaction flow tests for auth, listing, successful submission, idempotency replay, idempotency conflict, invalid amount, currency mismatch, balance updates, insufficient funds, failed transaction recording, idempotent retry balance safety, and balanced ledger entries.

### Not Implemented Yet

These are planned, not implemented:

- Richer system-account modeling beyond the seeded USD settlement account.
- Transaction reversal.
- Outbox events for posted transactions.
- Kafka publishing or consumption.
- Reconciliation.

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

## 5. Idempotency Flow

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

## 6. Failed Transaction Flow

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

## 7. Database Design

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
- `version`: optimistic concurrency field.
- `created_at`, `updated_at`: timestamps.

Important indexes:

- `idx_transactions_account_id`
- `idx_transactions_owner_created_at`

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

## 8. File-by-File Explanation

### `TransactionController.java`

Defines:

```text
POST /transactions
GET  /transactions
```

For submission, it reads:

- authenticated user id from `Authentication`
- idempotency key from the `Idempotency-Key` header
- transaction fields from `CreateTransactionRequest`

For listing, it reads the authenticated user id from `Authentication`.

Both methods delegate to `TransactionService`.

### `TransactionService.java`

Contains transaction command business logic:

- normalize and validate idempotency key
- return existing transaction for repeated owner/key pair
- list transactions by authenticated owner
- validate request body
- load account
- enforce account ownership
- enforce currency match
- save `PENDING` transaction
- calculate the new account balance
- update transaction to `FAILED` when insufficient funds occurs
- create user ledger entry
- create settlement ledger entry
- save the updated account
- update the transaction to `POSTED`

It is wrapped in `@Transactional(noRollbackFor = InsufficientFundsException.class)`.

Successful posting commits transaction creation, ledger entry creation, account balance update, and status update together. Insufficient funds still throws an API error, but the `FAILED` transaction row is committed.

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

### `CreateTransactionRequest.java`

Request DTO for `POST /transactions`.

Fields:

- `accountId`
- `type`
- `amountMinor`
- `currency`
- `description`

The idempotency key is intentionally not in this DTO. It comes from the HTTP `Idempotency-Key` header.

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

## 9. Design Decisions

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

## 10. Common Debugging Lessons

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

## 11. Interview Questions and Answers

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

## 12. Checklist Before Moving On

Before moving on to reversals or outbox, be able to explain:

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
