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
- account ownership derived from the authenticated JWT subject
- account request validation with clean `400` errors
- account flow tests covering protected access, creation, listing, invalid currency, and currency normalization
- `transactions` table
- `ledger_entries` table
- `POST /transactions`
- idempotency lookup through `Idempotency-Key`
- transaction ownership and currency validation
- transaction flow tests covering auth, successful submission, idempotency, invalid amount, and currency mismatch

Planned scope includes double-entry ledger posting, balance mutation, optimistic concurrency, transactional outbox, Spring Kafka consumers, reconciliation, and dead-letter replay.

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
- Transactions are scoped to users with `transactions.owner_user_id`.
- Transaction idempotency is scoped by `(owner_user_id, idempotency_key)`.
- Ledger entries must be derived from accepted transaction commands and remain auditable.

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
- Transaction tests should cover authentication, idempotency, ownership checks, validation, and currency mismatch.
- Ledger posting tests should cover balanced entries, insufficient funds, currency mismatch, reversals, and concurrent transaction races.

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

## What Not To Do

- Do not modify application code when asked only for planning, documentation, or explanation.
- Do not bypass Flyway with manual schema changes except for temporary local debugging.
- Do not edit already-applied migrations without resetting the local database.
- Do not make Kafka or caches the source of truth.
- Do not make protected application endpoints public by default.
- Do not store raw passwords, JWT secrets, or production credentials in source control.
- Do not add ledger posting, outbox, Kafka consumers, or reconciliation code before the current milestone calls for it.
