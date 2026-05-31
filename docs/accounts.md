# Accounts

This document explains the current LedgerFlow account API implementation. It focuses on what exists today: creating accounts, listing the authenticated user's accounts, listing account ledger entries, and persisting account rows in PostgreSQL.

## 1. Current Account Scope

### Implemented

The Spring Boot API currently supports:

- `accounts` table through Flyway migration `V3__create_accounts_table.sql`.
- Authenticated `POST /accounts`.
- Authenticated `GET /accounts`.
- Authenticated `GET /accounts/{accountId}/ledger-entries`.
- Account ownership from the JWT subject stored in Spring Security's `Authentication`.
- Java account model mapped with Spring Data JDBC.
- Spring Data repositories for saving/listing accounts and reading ledger entries.
- New accounts start as `ACTIVE`.
- New accounts start with `balanceMinor = 0`.
- Account `version` is annotated with `@Version` for optimistic concurrency support.
- Account request validation with clean `400` errors.
- Currency normalization from lowercase or padded input to uppercase.
- Account ledger entry listing verifies account ownership before returning rows.
- Account flow tests for authentication, creation, listing, invalid currency, currency normalization, and ledger entry listing.

### Not Implemented Yet

These are planned, not implemented:

- Per-user duplicate account rules.
- Account freezing or closing endpoints.
- Richer system-account modeling.
- Transactional outbox events.
- Kafka publishing.
- Reconciliation.

### Public Endpoints

None of the account endpoints are public.

### Protected Endpoints

All account endpoints require:

```http
Authorization: Bearer <access_token>
```

Current account endpoints:

```text
POST /accounts
GET  /accounts
GET  /accounts/{accountId}/ledger-entries
```

## 2. Runtime Flow

When the API starts, Flyway applies migrations from:

```text
services/ledger-api/src/main/resources/db/migration/
```

The account migration is:

```text
V3__create_accounts_table.sql
```

Startup flow:

```text
gradle bootRun
  |
  v
Spring Boot starts
  |
  +--> loads application.yml
  +--> connects to PostgreSQL
  +--> Flyway applies V1 users table
  +--> Flyway applies V2 admin seed
  +--> Flyway applies V3 accounts table
  +--> Spring creates AccountRepository
  +--> Spring creates AccountService
  +--> Spring creates AccountController
  |
  v
Tomcat listens on port 8080
```

The account API depends on the auth foundation:

```text
Bearer token
  |
  v
JwtAuthenticationFilter
  |
  v
SecurityContextHolder
  |
  v
AccountController receives Authentication
```

## 3. Create Account Flow

### Request

`POST /accounts`

```json
{
  "currency": "USD"
}
```

Required header:

```http
Authorization: Bearer <access_token>
```

### Step-by-Step

1. The request enters the Spring Security filter chain.
2. `JwtAuthenticationFilter` validates the bearer token.
3. The filter stores the user id as the authentication principal.
4. `AccountController.createAccount(...)` receives `Authentication` and `CreateAccountRequest`.
5. The controller reads `ownerUserId` from `authentication.getPrincipal()`.
6. The controller calls `AccountService.createAccount(ownerUserId, request)`.
7. `AccountService` validates and normalizes currency using `normalizeCurrency(...)`.
8. `AccountService` creates an `Account` record with:
   - random UUID
   - authenticated owner user id
   - normalized uppercase currency
   - `ACTIVE` status
   - `balanceMinor = 0`
   - `version = 0`
   - timestamps
9. `AccountRepository.save(account)` inserts the row into PostgreSQL.
10. `AccountResponse.from(savedAccount)` converts the database model into an API response.
11. Spring returns `201 Created`.

### Diagram

```text
Client
  |
  | POST /accounts
  | Authorization: Bearer <access_token>
  | {"currency":"USD"}
  v
Spring Security
  |
  +--> JwtAuthenticationFilter validates token
  +--> principal = user UUID from JWT sub claim
  |
  v
AccountController
  |
  +--> ownerUserId = authentication.getPrincipal()
  |
  v
AccountService
  |
  +--> validate currency
  +--> normalize currency
  +--> create ACTIVE account
  +--> balanceMinor = 0
  |
  v
AccountRepository
  |
  v
PostgreSQL accounts table
  |
  v
AccountResponse
```

### Response

Example:

```json
{
  "id": "7b0e7f61-6482-4ab0-8198-cc6216814a9c",
  "ownerUserId": "00000000-0000-0000-0000-000000000001",
  "currency": "USD",
  "status": "ACTIVE",
  "balanceMinor": 0,
  "version": 0
}
```

The `version` value may be managed by Spring Data JDBC. The important design point is that the version field prepares the model for optimistic concurrency.

## 4. List Accounts Flow

### Request

`GET /accounts`

Required header:

```http
Authorization: Bearer <access_token>
```

### Step-by-Step

1. The request enters the Spring Security filter chain.
2. `JwtAuthenticationFilter` validates the bearer token.
3. The filter stores the user id as the authentication principal.
4. `AccountController.listAccounts(...)` reads the principal.
5. The controller calls `AccountService.listAccounts(ownerUserId)`.
6. `AccountService` calls `AccountRepository.findByOwnerUserId(ownerUserId)`.
7. Spring Data JDBC queries the `accounts` table.
8. Each `Account` is converted to `AccountResponse`.
9. Spring returns `200 OK`.

### Diagram

```text
Client
  |
  | GET /accounts
  | Authorization: Bearer <access_token>
  v
Spring Security
  |
  +--> JwtAuthenticationFilter validates token
  +--> principal = user UUID
  |
  v
AccountController
  |
  v
AccountService
  |
  v
AccountRepository.findByOwnerUserId(ownerUserId)
  |
  v
PostgreSQL accounts table
  |
  v
List<AccountResponse>
```

## 5. List Account Ledger Entries Flow

### Request

`GET /accounts/{accountId}/ledger-entries`

Required header:

```http
Authorization: Bearer <access_token>
```

### Step-by-Step

1. The request enters the Spring Security filter chain.
2. `JwtAuthenticationFilter` validates the bearer token.
3. The filter stores the user id as the authentication principal.
4. `AccountController.listLedgerEntries(...)` reads the principal and path variable.
5. The controller calls `AccountService.listLedgerEntries(ownerUserId, accountId)`.
6. `AccountService` loads the account with `AccountRepository.findById(accountId)`.
7. If the account does not exist, `AccountNotFoundException` becomes `404 ACCOUNT_NOT_FOUND`.
8. If the account belongs to another user, `AccountOwnershipException` becomes `403 ACCOUNT_FORBIDDEN`.
9. `LedgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId)` loads the account's ledger rows newest first.
10. Each `LedgerEntry` is converted to `LedgerEntryResponse`.
11. Spring returns `200 OK`.

### Diagram

```text
Client
  |
  | GET /accounts/{accountId}/ledger-entries
  | Authorization: Bearer <access_token>
  v
Spring Security
  |
  +--> JwtAuthenticationFilter validates token
  +--> principal = user UUID
  |
  v
AccountController
  |
  v
AccountService
  |
  +--> AccountRepository.findById(accountId)
  +--> verify account.ownerUserId == authenticated user id
  |
  v
LedgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId)
  |
  v
PostgreSQL ledger_entries table
  |
  v
List<LedgerEntryResponse>
```

### Response

Example after a deposit:

```json
[
  {
    "id": "1cda0a10-bef1-4891-8e3b-c9375ef82d31",
    "transactionId": "caea1345-da6a-4a1a-9047-96345307e010",
    "accountId": "5e824b14-77a3-4db7-882b-4c06abc2dc8b",
    "direction": "CREDIT",
    "amountMinor": 1000,
    "currency": "USD",
    "createdAt": "2026-05-27T12:00:00+08:00"
  }
]
```

The endpoint returns entries for the requested account only. The offset settlement account entry is stored in `ledger_entries`, but it is not returned when listing the user's account ledger entries.

## 6. Database Design

The account table is created by:

```text
services/ledger-api/src/main/resources/db/migration/V3__create_accounts_table.sql
```

Current columns:

- `id`: account primary key.
- `owner_user_id`: foreign key to `users(id)`.
- `currency`: three-letter currency code such as `USD` or `SGD`.
- `status`: account status string.
- `balance_minor`: balance in minor units, such as cents.
- `version`: optimistic concurrency version.
- `created_at`: creation timestamp.
- `updated_at`: update timestamp.

Current constraints:

- `owner_user_id` must reference an existing user.
- `currency` must be uppercase.
- `currency` must have length 3.
- `status` must be one of `ACTIVE`, `FROZEN`, or `CLOSED`.
- `balance_minor` must be non-negative.

Why `balance_minor` instead of decimal dollars:

```text
$10.50 -> 1050
```

Money should not be represented with floating point numbers. Minor-unit integers avoid rounding errors.

## 7. File-by-File Explanation

### `AccountController.java`

Path:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/account/AccountController.java
```

Defines:

- `POST /accounts`
- `GET /accounts`
- `GET /accounts/{accountId}/ledger-entries`

It is a thin HTTP adapter. It reads the authenticated user id from `Authentication`, reads path variables when needed, then delegates to `AccountService`.

### `AccountService.java`

Contains account business logic for the current slice:

- validate required currency
- validate 3-letter currency format
- normalize currency
- create account defaults
- save account
- list accounts by owner
- verify account ownership for ledger entry listing
- list ledger entries by account
- convert models to responses

This is where future transaction boundaries should live.

### `InvalidAccountRequestException.java`

Specific exception for invalid account creation requests.

Current examples:

- missing currency
- blank currency
- currency that is not exactly three letters

`GlobalExceptionHandler` maps this exception to `400 Bad Request`.

### `AccountNotFoundException.java`

Specific exception for account lookups where the submitted account id does not exist.

`GlobalExceptionHandler` maps this exception to `404 ACCOUNT_NOT_FOUND`.

### `AccountOwnershipException.java`

Specific exception for account access where the account exists but belongs to another user.

`GlobalExceptionHandler` maps this exception to `403 ACCOUNT_FORBIDDEN`.

### `AccountRepository.java`

Extends:

```java
CrudRepository<Account, UUID>
```

This gives basic persistence methods such as `save(...)` and `findById(...)`.

It also declares:

```java
List<Account> findByOwnerUserId(UUID ownerUserId);
```

Spring Data JDBC derives the query from the method name.

### `Account.java`

Spring Data JDBC model mapped to:

```text
accounts
```

Important annotations:

- `@Table("accounts")`
- `@Id`
- `@Version`

`@Version` matters because accounts use Java-generated UUIDs. Without a version field, Spring Data JDBC can mistake a new object with a non-null id for an existing row.

### `AccountStatus.java`

Enum for allowed account states:

- `ACTIVE`
- `FROZEN`
- `CLOSED`

Only `ACTIVE` is used by the current create endpoint.

### `CreateAccountRequest.java`

Request DTO for:

```text
POST /accounts
```

Current field:

- `currency`

### `AccountResponse.java`

Response DTO for account endpoints.

Current fields:

- `id`
- `ownerUserId`
- `currency`
- `status`
- `balanceMinor`
- `version`

It has a static `from(Account account)` mapper to keep model-to-response conversion consistent.

### `LedgerEntryResponse.java`

Response DTO for account ledger entry listing.

Current fields:

- `id`
- `transactionId`
- `accountId`
- `direction`
- `amountMinor`
- `currency`
- `createdAt`

It has a static `from(LedgerEntry ledgerEntry)` mapper to keep ledger model-to-response conversion consistent.

### `V3__create_accounts_table.sql`

Flyway migration that creates the `accounts` table and index.

### `AccountFlowTest.java`

Test class under:

```text
services/ledger-api/src/test/java/com/fanryan/ledgerflow/account/AccountFlowTest.java
```

It covers:

- `POST /accounts` requires authentication.
- valid JWT can create an account.
- created account belongs to the seeded admin user.
- `GET /accounts` requires authentication.
- valid JWT can list current user's accounts.
- invalid currency returns `INVALID_ACCOUNT_REQUEST`.
- lowercase currency is normalized before saving.
- `GET /accounts/{accountId}/ledger-entries` requires authentication.
- valid JWT can list ledger entries for an owned account after transaction posting.

## 8. Key Spring Concepts

### `@RestController`

Marks `AccountController` as an HTTP controller that returns JSON response bodies.

### `@Service`

Marks `AccountService` as application/business logic managed by Spring.

### `CrudRepository`

Spring Data interface that provides basic persistence methods.

For LedgerFlow:

```java
CrudRepository<Account, UUID>
```

means the repository stores `Account` objects and their primary key type is `UUID`.

### Derived Query Methods

`findByOwnerUserId(...)` is not manually implemented.

Spring Data reads the method name and creates the SQL query for the `owner_user_id` column.

### `Authentication`

Spring Security injects the current request's authenticated identity into controller methods.

LedgerFlow stores the user UUID as the authentication principal after JWT validation.

### `@Id`

Marks the primary key field of the Spring Data JDBC model.

### `@Version`

Marks the optimistic concurrency version field.

It also helps Spring Data JDBC understand whether a record with a Java-generated UUID should be inserted as new.

### `record`

Java's compact immutable data carrier.

LedgerFlow uses records for database rows and request/response DTOs in this slice.

## 9. Design Decisions

### Why Account Endpoints Require JWT

Accounts belong to users. Creating or listing accounts without authentication would make ownership impossible to enforce.

### Why Owner User ID Comes From Authentication

The client does not send `ownerUserId`.

This prevents a caller from creating accounts for another user by spoofing a user id in JSON.

### Why New Accounts Start With Zero Balance

Balances change through transaction posting, not through account creation.

### Why Currency Is Uppercased in the Service

The database requires uppercase currency codes. The service normalizes input like `usd` into `USD`.

### Why Account Validation Exists in the Service

The database protects invalid states, but API clients should receive clean `400` responses before hitting database constraints.

Examples:

- missing currency
- currency longer or shorter than 3 characters
- non-letter currency

Unsupported currency allow-listing is still not implemented. Today, any three-letter alphabetic code is accepted.

### Why Ledger Entry Listing Lives Under Accounts

Ledger entries are the audit trail for balance movement on an account.

The URL:

```text
GET /accounts/{accountId}/ledger-entries
```

starts from the account because ownership must be checked before ledger rows are returned.

### Why Account Ownership Is Checked Before Ledger Entries

Ledger entries reveal account movement. A caller must not be able to inspect another user's account history by guessing an account UUID.

The service first verifies:

```text
account.ownerUserId == authenticated user id
```

Only then does it query `ledger_entries`.

### Why `@Version` Exists Already

The project will need optimistic concurrency for account balance updates. The version field prepares the account model for safe updates later.

## 10. Common Debugging Lessons

### `CrudRepository` Raw Type

If `AccountRepository` is declared like this:

```java
public interface AccountRepository extends CrudRepository
```

Spring cannot know the domain type.

Correct:

```java
public interface AccountRepository extends CrudRepository<Account, UUID>
```

### `POST /accounts` Returned 403 While `GET /accounts` Worked

The token and security rules were fine because `GET /accounts` returned `200`.

The issue was Spring Data JDBC new-row detection. Since the account had a Java-generated UUID, the id was non-null before save.

Adding `@Version` let Spring Data JDBC treat the account as a new insert.

### Missing Authorization Header

Without:

```http
Authorization: Bearer <access_token>
```

account endpoints are blocked by Spring Security.

### Flyway Migration Already Applied

If `V3__create_accounts_table.sql` has already run, editing it directly will not be picked up by Flyway.

For local-only mistakes, reset local volumes:

```bash
docker compose down -v
```

For committed/shared schema changes, create a new migration.

### Invalid Currency Returns 400

Bad account input is handled before database insert.

Example:

```json
{
  "currency": "USDD"
}
```

returns:

```json
{
  "errorCode": "INVALID_ACCOUNT_REQUEST",
  "message": "Currency must be a 3-letter code",
  "requestId": "...",
  "timestamp": "..."
}
```

### Ledger Entry Listing Returns 404 Or 403

For:

```text
GET /accounts/{accountId}/ledger-entries
```

`404 ACCOUNT_NOT_FOUND` means the account id does not exist.

`403 ACCOUNT_FORBIDDEN` means the account exists but does not belong to the authenticated user.

## 11. Interview Questions and Answers

1. **What does the account API currently do?**  
   It lets an authenticated user create an account, list their own accounts, and list ledger entries for an owned account.

2. **How does the API know who owns the account?**  
   It reads the user UUID from `Authentication.getPrincipal()`, which was populated by the JWT filter.

3. **Why not accept `ownerUserId` in the request body?**  
   That would let clients spoof ownership. The server derives ownership from the authenticated identity.

4. **What does `balanceMinor` mean?**  
   It stores money in minor units such as cents, so `$10.50` is stored as `1050`.

5. **Why avoid floating point for money?**  
   Floating point can introduce rounding errors. Integer minor units are deterministic.

6. **What status does a new account start with?**  
   `ACTIVE`.

7. **What balance does a new account start with?**  
   `0`.

8. **What does `AccountRepository` do?**  
   It provides Spring Data JDBC persistence for `Account` rows.

9. **How does `findByOwnerUserId` work?**  
   Spring Data derives a query from the method name and maps it to the `owner_user_id` column.

10. **Why does `Account` use `@Version`?**  
    It prepares the model for optimistic concurrency and helps Spring Data JDBC insert Java-generated UUID entities correctly.

11. **What does `POST /accounts` return?**  
    `201 Created` with an `AccountResponse`.

12. **What does `GET /accounts` return?**  
    `200 OK` with a list of `AccountResponse` objects for the current user.

13. **What is not implemented yet for accounts?**  
    Currency allow-listing, freezing, closing, richer system-account modeling, reversals, and concurrency hardening.

14. **Why is account creation not allowed to set an opening balance?**  
    Balance changes go through transaction posting to keep account movement auditable.

15. **What does the accounts table foreign key enforce?**  
    Every account owner must exist in the `users` table.

16. **What happens if currency is lowercase?**  
    `AccountService` uppercases it before saving.

17. **What happens if currency is invalid length today?**  
    `AccountService` throws `InvalidAccountRequestException`, and the API returns `400` with `INVALID_ACCOUNT_REQUEST`.

18. **Why do account endpoints need tests if curl worked?**  
    Tests protect the flow from regressions and prove behavior through Spring MVC and Spring Security.

19. **What does `AccountResponse.from(...)` do?**  
    It converts the database model into the JSON response shape.

20. **How does this account slice prepare for transaction posting?**  
    It establishes user-owned accounts, balances in minor units, statuses, and optimistic concurrency versioning.

21. **Why does ledger entry listing verify account ownership first?**  
    Ledger entries expose account movement, so the service must prove the account belongs to the authenticated user before returning them.

22. **Why use `LedgerEntryResponse` instead of returning `LedgerEntry` directly?**  
    It keeps API response shape separate from persistence models and avoids leaking fields that are not part of the public contract.

## 12. Checklist Before Moving On

Before extending account behavior, be able to explain:

- [ ] Why account endpoints are protected.
- [ ] How JWT authentication reaches `AccountController`.
- [ ] Why `ownerUserId` comes from `Authentication`.
- [ ] What fields exist in the `accounts` table.
- [ ] Why `balanceMinor` is an integer.
- [ ] Why new accounts start at zero balance.
- [ ] Why `AccountStatus` exists.
- [ ] How `AccountRepository.findByOwnerUserId(...)` works.
- [ ] Why `@Version` is on the `version` field.
- [ ] What `AccountFlowTest` proves.
- [ ] How account ledger entry listing verifies ownership.
- [ ] Why `LedgerEntryResponse` exists.
- [ ] How invalid account requests become clean `400` responses.
- [ ] What is still missing before production-grade account APIs.
- [ ] Why balance changes go through transactions instead of account creation.
