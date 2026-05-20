# Transactions

This document explains the current LedgerFlow transaction submission foundation. It focuses on what exists today: accepting authenticated transaction commands, validating ownership and currency, enforcing idempotency, and storing `PENDING` transaction rows.

It does not describe balance mutation or double-entry ledger posting as implemented features. The `ledger_entries` table and Java model exist as foundation, but transaction posting into balanced ledger entries is planned next.

## 1. Current Transaction Scope

### Implemented

The Spring Boot API currently supports:

- `transactions` table through Flyway migration `V4__create_transactions_table.sql`.
- `ledger_entries` table through Flyway migration `V5__create_ledger_entries_table.sql`.
- Authenticated `POST /transactions`.
- `Idempotency-Key` request header.
- Idempotency lookup scoped by `(owner_user_id, idempotency_key)`.
- Account existence check before transaction creation.
- Account ownership check before transaction creation.
- Transaction currency validation against the account currency.
- Request validation for required account id, type, positive amount, currency, and idempotency key.
- Transaction rows created as `PENDING`.
- Java models and repositories for `Transaction` and `LedgerEntry`.
- Transaction flow tests for auth, successful submission, idempotency, invalid amount, and currency mismatch.

### Not Implemented Yet

These are planned, not implemented:

- Creating debit/credit ledger entries from a transaction.
- Updating account balances.
- Marking transactions `POSTED` after successful ledger posting.
- Marking transactions `FAILED` after business-rule failures.
- Insufficient funds handling.
- Withdrawal posting rules.
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
  "status": "PENDING",
  "description": "Test deposit"
}
```

The transaction remains `PENDING` because ledger posting is not implemented yet.

## 3. Runtime Flow

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
  |
  v
PostgreSQL transactions table
```

## 4. Idempotency Flow

The current idempotency rule is:

```text
One owner user id + one idempotency key = one transaction result
```

Database constraint:

```sql
CONSTRAINT transactions_idempotency_unique_per_owner
UNIQUE (owner_user_id, idempotency_key)
```

Service lookup:

```java
findByOwnerUserIdAndIdempotencyKey(ownerUserId, normalizedIdempotencyKey)
```

If a matching transaction exists, the service returns it instead of creating a duplicate.

```text
First request
  |
  +--> no existing owner/key row
  +--> create transaction A

Second request with same owner/key
  |
  +--> transaction A already exists
  +--> return transaction A
```

Current note: the controller still returns `201 Created` for both first and repeated idempotent submissions because it is annotated with `@ResponseStatus(HttpStatus.CREATED)`. The important behavior today is duplicate prevention and same-result return.

## 5. Database Design

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

Important indexes:

- `idx_ledger_entries_transaction_id`
- `idx_ledger_entries_account_created_at`

The ledger table exists now, but no code writes ledger entries yet.

## 6. File-by-File Explanation

### `TransactionController.java`

Defines:

```text
POST /transactions
```

It reads:

- authenticated user id from `Authentication`
- idempotency key from the `Idempotency-Key` header
- transaction fields from `CreateTransactionRequest`

Then it delegates to `TransactionService`.

### `TransactionService.java`

Contains transaction command business logic:

- normalize and validate idempotency key
- return existing transaction for repeated owner/key pair
- validate request body
- load account
- enforce account ownership
- enforce currency match
- save `PENDING` transaction

It does not yet post ledger entries or mutate balances.

### `TransactionRepository.java`

Extends:

```java
CrudRepository<Transaction, UUID>
```

Important derived queries:

- `findByOwnerUserIdAndIdempotencyKey(...)`
- `findByOwnerUserIdOrderByCreatedAtDesc(...)`
- `findByAccountIdOrderByCreatedAtDesc(...)`

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

Only `PENDING` is produced today.

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
400 INVALID_TRANSACTION_REQUEST
```

### `AccountNotFoundException.java`

Thrown when the submitted account id does not exist.

Mapped to:

```text
404 ACCOUNT_NOT_FOUND
```

### `AccountOwnershipException.java`

Thrown when the target account exists but does not belong to the authenticated user.

Mapped to:

```text
403 ACCOUNT_FORBIDDEN
```

### `LedgerEntry.java`

Spring Data JDBC model for the `ledger_entries` table.

No service currently writes this model.

### `LedgerEntryRepository.java`

Repository for future ledger entry lookups.

Important derived queries:

- `findByTransactionId(...)`
- `findByAccountIdOrderByCreatedAtDesc(...)`

### `LedgerEntryDirection.java`

Enum:

- `DEBIT`
- `CREDIT`

## 7. Design Decisions

### Why Idempotency Uses a Header

Idempotency is request metadata, not transaction business content. This mirrors payment-style APIs where clients retry the same logical request with the same key.

### Why Idempotency Is Scoped By Owner

Two different users should be able to use the same idempotency key without colliding.

The unique constraint is:

```text
owner_user_id + idempotency_key
```

### Why Account Ownership Is Checked In The Service

The request includes `accountId`, but the user identity comes from the JWT.

The service loads the account and verifies:

```text
account.ownerUserId == authenticated user id
```

This prevents users from posting transactions to accounts they do not own.

### Why Transactions Start As `PENDING`

The current endpoint records the command, but does not yet perform full ledger posting.

Later, successful posting should create balanced ledger entries, update balances, and mark the transaction `POSTED`.

### Why Ledger Entries Exist Before Posting Logic

The table and Java model create the accounting foundation. The next implementation step can focus on business logic instead of schema design.

## 8. Common Debugging Lessons

### Reused Idempotency Keys Return Old Results

If a test or curl request reuses the same `Idempotency-Key`, the API should return the existing transaction.

That is correct behavior. Use a unique key when testing new transaction creation.

### Java-Generated UUIDs Need `@Version`

Without `@Version`, Spring Data JDBC may treat an object with a non-null UUID as an existing row and try to update instead of insert.

This was fixed by adding:

```java
@Version long version
```

to `Transaction`.

### `403` Can Hide An Internal Error

If an endpoint throws unexpectedly and the app forwards to `/error`, Spring Security may return `403` because `/error` is protected.

When this happens, check the application logs or test report for the underlying exception.

## 9. Interview Questions and Answers

1. **What does the transaction endpoint currently do?**  
   It accepts an authenticated transaction command and stores it as a `PENDING` transaction.

2. **Does it update account balances today?**  
   No. Balance mutation is planned for the ledger posting step.

3. **Does it create ledger entries today?**  
   No. The ledger table/model exists, but no service writes entries yet.

4. **Why use an `Idempotency-Key` header?**  
   It lets clients safely retry the same request without creating duplicate transactions.

5. **How is idempotency scoped?**  
   By `(owner_user_id, idempotency_key)`.

6. **What happens when the same user sends the same idempotency key again?**  
   The service returns the existing transaction instead of creating a new row.

7. **Why does the transaction table include `owner_user_id`?**  
   It scopes idempotency and supports user-specific transaction queries.

8. **Why check account ownership?**  
   A user must not submit transactions against another user's account.

9. **Why validate currency against the account?**  
   A transaction should not post `SGD` against a `USD` account.

10. **Why are amounts stored as `amount_minor`?**  
    Minor-unit integers avoid floating point money errors.

11. **Why does `Transaction` have `@Version`?**  
    It helps Spring Data JDBC insert Java-generated UUID rows correctly and prepares for optimistic concurrency.

12. **Why does the response status remain `PENDING`?**  
    The command is accepted, but ledger posting has not been implemented yet.

13. **What will make a transaction `POSTED` later?**  
    Successful balanced ledger entry creation and balance update.

14. **What will make a transaction `FAILED` later?**  
    Business-rule failure such as insufficient funds or invalid posting state.

15. **Why separate `transactions` from `ledger_entries`?**  
    Transactions are user commands; ledger entries are accounting facts.

## 10. Checklist Before Moving On

Before implementing ledger posting, be able to explain:

- [ ] Why `POST /transactions` requires authentication.
- [ ] Why idempotency key lives in a header.
- [ ] How repeated idempotency keys return the same transaction.
- [ ] Why account ownership is checked after loading the account.
- [ ] Why transaction currency must match account currency.
- [ ] Why transactions currently start as `PENDING`.
- [ ] What the `transactions` table stores.
- [ ] What the `ledger_entries` table will store.
- [ ] Why `Transaction` has `@Version`.
- [ ] What `TransactionFlowTest` proves.
- [ ] What is still missing before real money movement is implemented.
