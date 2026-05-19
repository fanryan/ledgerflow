# Accounts

This document explains the current LedgerFlow account API implementation. It focuses on what exists today: creating accounts, listing the authenticated user's accounts, and persisting account rows in PostgreSQL.

It does not describe transaction posting or double-entry ledger movement as implemented features. Those are planned next layers.

## 1. Current Account Scope

### Implemented

The Spring Boot API currently supports:

- `accounts` table through Flyway migration `V3__create_accounts_table.sql`.
- Authenticated `POST /accounts`.
- Authenticated `GET /accounts`.
- Account ownership from the JWT subject stored in Spring Security's `Authentication`.
- Java account model mapped with Spring Data JDBC.
- Spring Data repository for saving and listing accounts.
- New accounts start as `ACTIVE`.
- New accounts start with `balanceMinor = 0`.
- Account `version` is annotated with `@Version` for optimistic concurrency support.
- Account flow tests for authentication, creation, and listing.

### Not Implemented Yet

These are planned, not implemented:

- Request validation with friendly `400` responses for invalid currency.
- Per-user duplicate account rules.
- Account freezing or closing endpoints.
- Balance mutation APIs.
- Transaction posting.
- Double-entry ledger entries.
- Idempotency keys.
- Transactional outbox events.
- Kafka publishing.
- Reconciliation.

### Public Endpoints

None of the account endpoints are public.

### Protected Endpoints

Both account endpoints require:

```http
Authorization: Bearer <access_token>
```

Current account endpoints:

```text
POST /accounts
GET  /accounts
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
7. `AccountService` uppercases the currency using `Locale.ROOT`.
8. `AccountService` creates an `Account` record with:
   - random UUID
   - authenticated owner user id
   - uppercase currency
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
  +--> uppercase currency
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

## 5. Database Design

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

## 6. File-by-File Explanation

### `AccountController.java`

Path:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/account/AccountController.java
```

Defines:

- `POST /accounts`
- `GET /accounts`

It is a thin HTTP adapter. It reads the authenticated user id from `Authentication`, then delegates to `AccountService`.

### `AccountService.java`

Contains account business logic for the current slice:

- uppercase currency
- create account defaults
- save account
- list accounts by owner
- convert models to responses

This is where future validation and transaction boundaries should live.

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

## 7. Key Spring Concepts

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

## 8. Design Decisions

### Why Account Endpoints Require JWT

Accounts belong to users. Creating or listing accounts without authentication would make ownership impossible to enforce.

### Why Owner User ID Comes From Authentication

The client does not send `ownerUserId`.

This prevents a caller from creating accounts for another user by spoofing a user id in JSON.

### Why New Accounts Start With Zero Balance

Balances should change through future ledger-backed transaction posting, not through account creation.

### Why Currency Is Uppercased in the Service

The database requires uppercase currency codes. The service normalizes input like `usd` into `USD`.

### Why More Validation Is Still Needed

The database currently protects some invalid states, but API clients should eventually receive clean `400` responses before hitting database constraints.

Examples:

- missing currency
- currency longer or shorter than 3 characters
- unsupported currency

### Why `@Version` Exists Already

The project will need optimistic concurrency for account balance updates. The version field prepares the account model for safe updates later.

## 9. Common Debugging Lessons

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

## 10. Interview Questions and Answers

1. **What does the account API currently do?**  
   It lets an authenticated user create an account and list their own accounts.

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
    Friendly validation errors, freezing, closing, balance mutations, transactions, and ledger entries.

14. **Why is account creation not allowed to set an opening balance?**  
    Balance changes should go through future ledger-backed transactions to keep accounting auditable.

15. **What does the accounts table foreign key enforce?**  
    Every account owner must exist in the `users` table.

16. **What happens if currency is lowercase?**  
    `AccountService` uppercases it before saving.

17. **What happens if currency is invalid length today?**  
    The database constraint can reject it, but a clean API validation response is still planned.

18. **Why do account endpoints need tests if curl worked?**  
    Tests protect the flow from regressions and prove behavior through Spring MVC and Spring Security.

19. **What does `AccountResponse.from(...)` do?**  
    It converts the database model into the JSON response shape.

20. **How does this account slice prepare for transaction posting?**  
    It establishes user-owned accounts, balances in minor units, statuses, and optimistic concurrency versioning.

## 11. Checklist Before Moving On

Before starting transaction and ledger tables, be able to explain:

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
- [ ] What is still missing before production-grade account APIs.
- [ ] Why transaction posting must be ledger-backed instead of directly editing balances.
