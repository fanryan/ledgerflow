# AGENTS.md

## Project Overview

LedgerFlow is a production-inspired transaction processing and reconciliation platform built as a Spring Boot backend systems project.

- Spring Boot owns the API layer, transactional ledger logic, outbox publishing, Kafka consumers, and reconciliation.
- PostgreSQL is the source of truth.
- Kafka is used for event-driven processing.
- Docker Compose runs local infrastructure.
- Flyway manages Spring Boot database migrations.

Current implemented Spring Boot slice:

- `GET /health`
- `POST /auth/login`
- `POST /auth/refresh`
- PostgreSQL connection
- Flyway migrations
- `users` table
- seeded local admin user
- BCrypt password verification
- JWT access and refresh token generation
- JWT validation filter
- `GET /auth/me`
- auth error response handling for invalid credentials and invalid tokens
- auth flow tests covering login, refresh, invalid credentials, invalid tokens, and `/auth/me`
- `accounts` table
- `POST /accounts`
- `GET /accounts`
- `GET /accounts/{accountId}/ledger-entries`
- account ownership derived from the authenticated JWT subject
- account request validation with clean `400` errors
- account flow tests covering protected access, creation, listing, invalid currency, currency normalization, and account ledger entry listing
- `transactions` table
- `idempotency_keys` table
- `ledger_entries` table
- `POST /transactions`
- `GET /transactions`
- `POST /transactions/{transactionId}/reverse`
- idempotency lookup through `Idempotency-Key`, request hash, and stored response metadata
- duplicate idempotency key with a different payload returns `409`
- transaction ownership and currency validation
- transaction flow tests covering auth, successful submission, idempotency replay, idempotency conflict, invalid amount, and currency mismatch
- transaction posting updates account balances
- successful transactions return `POSTED`
- deposit and withdrawal create balanced ledger entries
- frozen and closed accounts cannot submit transactions
- USD settlement system account is seeded for offset entries
- insufficient funds returns `422`
- insufficient funds records a `FAILED` transaction row
- idempotent retries must not update balances twice
- transaction reversals create offsetting transactions and balanced ledger entries
- reversal requests require an idempotency key and reason
- idempotent reversal retries must return the existing reversal without changing balances twice
- reusing a reversal idempotency key with a different payload returns `409`
- optimistic locking conflicts return `409 CONCURRENT_TRANSACTION_CONFLICT`
- concurrent withdrawal tests prove racing requests do not overdraw an account
- `outbox_events` table
- posted transactions and reversals write `TRANSACTION_POSTED` outbox rows in the same database transaction
- outbox payloads are stored as PostgreSQL `jsonb`
- outbox event creation test verifies pending outbox rows are created
- claim-based outbox publisher uses `FOR UPDATE SKIP LOCKED`
- outbox claims use `locked_until` for stale claim recovery
- scheduled Spring Boot publisher sends outbox payloads to Kafka topic `ledger.events`
- Spring Kafka consumer reads `ledger.events`
- `consumed_ledger_events` table records consumed `TRANSACTION_POSTED` events
- consumed event inserts are idempotent through `(transaction_id, event_type)` uniqueness
- consumed `TRANSACTION_POSTED` events have been manually verified in PostgreSQL
- published events are marked `PUBLISHED`
- failed publishes are marked `FAILED` with retry metadata
- outbox repository, publisher service, consumer, and consumed-event repository tests cover claim, publish, failure, consumption, and duplicate-consumption paths

Planned scope includes richer system-account modeling, PayFlow event consumption, balance snapshots, reconciliation, and dead-letter replay.

## Architecture Rules

- PostgreSQL is the source of truth. Do not treat Kafka, caches, or consumer state as authoritative.
- Spring Boot handles request/response command workflows and transactional orchestration.
- Spring Boot components handle asynchronous workloads such as outbox publishing, Kafka consumption, reconciliation, and dead-letter replay.
- Kafka events should be derived from committed database state, usually through the transactional outbox pattern.
- Keep synchronous business writes and outbox writes in the same PostgreSQL transaction when outbox work begins.

## Repository Layout

```text
services/ledger-api/       Spring Boot API service
shared/schemas/            Shared event/schema definitions
infrastructure/            Docker/Kafka/local infra support files
scripts/                   Local helper scripts
loadtests/                 k6 or benchmark assets
docs/                      Architecture and design notes
docker-compose.yml         Local infrastructure entrypoint
```

## Spring Boot Service Conventions

- Work under `services/ledger-api`.
- Use Java 21 and Spring Boot 3.
- Follow Controller -> Service -> Repository layering.
- Controllers should be thin HTTP adapters.
- Services should contain business logic and transaction orchestration.
- Put `@Transactional` boundaries at the service layer only.
- Repositories should own database access through Spring Data JDBC or explicit JDBC patterns.
- Protected controllers should derive the current user id from `Authentication.getPrincipal()`, not from request bodies.
- Keep package names under `com.fanryan.ledgerflow`.
- Keep configuration in `src/main/resources/application.yml` unless a secret should come from the environment.
- Do not add JPA/Hibernate unless the project deliberately changes away from Spring Data JDBC.
- Use a global `@ControllerAdvice` for HTTP exception handling.
- Map API errors to the current standard error response shape: `errorCode`, `message`, `requestId`, and `timestamp`.

Useful commands:

```bash
cd services/ledger-api
gradle test
gradle bootRun
```

## Async Processing Conventions

- Keep asynchronous LedgerFlow components inside the Spring Boot service unless the architecture is deliberately changed later.
- Outbox publishing should read committed PostgreSQL outbox rows and publish them to Kafka.
- Kafka consumers should be idempotent and retry-safe.
- Current `LedgerEventsConsumer` records consumed events for audit/idempotency; do not treat it as a projection, reconciliation, or dead-letter implementation.
- Reconciliation should compare authoritative PostgreSQL state against derived or external state.
- Dead-letter replay should be explicit, auditable, and safe to retry.

## Database and Migration Rules

- All schema changes must go through Flyway migrations in:

```text
services/ledger-api/src/main/resources/db/migration/
```

- Use Flyway naming:

```text
V<number>__description.sql
```

- Already-applied migrations should not be edited unless the local database is reset.
- For local-only mistakes, resetting with `docker compose down -v` is acceptable.
- For committed/shared schema evolution, create a new migration instead of rewriting history.
- PostgreSQL owns authoritative account, transaction, ledger, idempotency, outbox, and reconciliation state.
- Account rows belong to users through `accounts.owner_user_id`.
- Account balances are stored in minor units using `balance_minor`.
- Account updates should preserve optimistic concurrency through the Spring Data `@Version` field.
- Account ledger entry listing must verify account ownership before returning ledger rows.
- Transactions are scoped to users with `transactions.owner_user_id`.
- Transaction idempotency is enforced through `idempotency_keys.key`, `request_hash`, `transaction_id`, `response_status`, and `response_body`.
- Reusing an idempotency key with the same payload must replay the original transaction result.
- Reusing an idempotency key with a different payload must return `409 IDEMPOTENCY_CONFLICT`.
- Failed transaction rows should remain queryable when a business-rule failure has already been accepted as a transaction command.
- Ledger entries must be derived from accepted transaction commands and remain auditable.
- Current ledger posting creates balanced entries between the user account and the seeded USD settlement system account.
- Idempotent transaction retries must return the existing transaction without creating additional ledger entries or balance changes.
- Transaction reversals must be modeled as new offsetting transactions, not deletes or destructive edits.
- Reversal metadata lives on `transactions.reversal_of_transaction_id` and `transactions.reversed_at`.
- Reversal idempotency must use the same `idempotency_keys` conflict rules as normal submissions.
- Account balance updates rely on optimistic locking through the account `@Version` field.
- Concurrent account balance write conflicts should return `409 CONCURRENT_TRANSACTION_CONFLICT`, not leak framework exceptions.
- Outbox rows must be written in the same PostgreSQL transaction as the business change they describe.
- Outbox event payloads are PostgreSQL `jsonb`; use explicit JDBC/SQL when JSONB binding or claim queries need database-specific behavior.
- `TRANSACTION_POSTED` events should use aggregate type `TRANSACTION` and aggregate id equal to the transaction id.
- Outbox publishers should claim rows with `FOR UPDATE SKIP LOCKED`, mark claimed rows as `PROCESSING`, and clear claim fields after `PUBLISHED` or `FAILED`.
- Outbox claims should use `locked_until` so crashed publishers can be recovered by later publisher runs.

## Security Rules

- `/health` is public.
- `/auth/login` is public.
- `/auth/refresh` is public.
- Application endpoints should require authentication by default.
- Use BCrypt for password verification. Never store raw passwords.
- JWT access tokens are short-lived API credentials.
- JWT refresh tokens are longer-lived credentials for token renewal.
- Current refresh tokens are stateless JWTs. Rotation/revocation storage is not implemented yet.
- JWTs should include and preserve the expected claims: `sub` for user id, `role` for authorization, `iat` for issued-at time, `exp` for expiry, and `jti` for token id.
- Protected endpoints should derive user identity and role from the validated JWT/security context, not from request bodies.
- Keep stateless API behavior: CSRF, form login, HTTP basic, and server-side sessions should remain disabled unless intentionally changed.
- Role-based authorization should use the project roles: `USER`, `ADMIN`, and `OPERATOR`.

## Testing Expectations

- Run Spring tests before committing API changes:

```bash
cd services/ledger-api
gradle test
```

- Validate Docker Compose after infrastructure edits:

```bash
docker compose config
```

- Future integration tests should use Testcontainers for PostgreSQL and Kafka.
- Auth tests should cover valid login, invalid login, token generation, and endpoint authorization.
- Account tests should cover protected access, account creation, ownership from JWT subject, listing by current user, invalid request handling, and normalization.
- Transaction tests should cover authentication, listing by current user, idempotency, ownership checks, validation, currency mismatch, balance updates, and insufficient funds.
- Reversal tests should cover offsetting transaction creation, balance restoration, double-reversal rejection, required reason validation, and idempotency.
- Concurrency tests should cover simultaneous withdrawals and verify the final account balance cannot go negative.
- Outbox tests should verify posted transactions create pending outbox events, repository claim transitions, successful publish marking, failed publish retry metadata, consumer payload handling, and duplicate consumed-event safety.
- Ledger posting tests should cover ledger entry creation, idempotent retry safety, and balanced debits/credits.

## Local Development Commands

From the repo root:

```bash
docker compose up -d
docker compose up -d postgres
docker compose down
docker compose down -v
docker compose config
```

Run the API:

```bash
cd services/ledger-api
gradle bootRun
```

Check health:

```bash
curl http://localhost:8080/health
```

Log in with the seeded local admin:

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@ledgerflow.local","password":"password"}'
```

Check the current authenticated user:

```bash
curl http://localhost:8080/auth/me \
  -H "Authorization: Bearer <access_token>"
```

Refresh tokens:

```bash
curl -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refresh_token>"}'
```

Create an account:

```bash
curl -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -d '{"currency":"USD"}'
```

List accounts:

```bash
curl http://localhost:8080/accounts \
  -H "Authorization: Bearer <access_token>"
```

List account ledger entries:

```bash
curl http://localhost:8080/accounts/<account_id>/ledger-entries \
  -H "Authorization: Bearer <access_token>"
```

Submit a transaction:

```bash
curl -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -H "Idempotency-Key: tx-example-001" \
  -d '{
    "accountId": "<account_id>",
    "type": "DEPOSIT",
    "amountMinor": 1000,
    "currency": "USD",
    "description": "Example deposit"
  }'
```

List transactions:

```bash
curl http://localhost:8080/transactions \
  -H "Authorization: Bearer <access_token>"
```

Reverse a transaction:

```bash
curl -X POST http://localhost:8080/transactions/<transaction_id>/reverse \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -H "Idempotency-Key: reverse-example-001" \
  -d '{"reason":"Customer requested reversal"}'
```

## What Not To Do

- Do not modify application code when asked only for planning, documentation, or explanation.
- Do not bypass Flyway with manual schema changes except for temporary local debugging.
- Do not edit already-applied migrations without resetting the local database.
- Do not make Kafka or caches the source of truth.
- Do not make protected application endpoints public by default.
- Do not store raw passwords, JWT secrets, or production credentials in source control.
- Do not add Kafka consumers or reconciliation code before the current milestone calls for it.
