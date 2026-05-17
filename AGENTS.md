# AGENTS.md

## Project Overview

LedgerFlow is a production-inspired transaction processing and reconciliation platform built as a polyglot backend systems project.

- Spring Boot owns the synchronous API layer.
- Go owns asynchronous infrastructure workers.
- PostgreSQL is the source of truth.
- Kafka is used for event-driven processing.
- Docker Compose runs local infrastructure.
- Flyway manages Spring Boot database migrations.

Current implemented Spring Boot slice:

- `GET /health`
- `POST /auth/login`
- PostgreSQL connection
- Flyway migrations
- `users` table
- seeded local admin user
- BCrypt password verification
- JWT access and refresh token generation
- JWT validation filter
- `GET /auth/me`
- auth error response handling for invalid credentials

Planned scope includes account APIs, idempotent transaction submission, double-entry ledger entries, optimistic concurrency, transactional outbox, Go Kafka workers, reconciliation, and dead-letter replay.

## Architecture Rules

- PostgreSQL is the source of truth. Do not treat Kafka, Redis, caches, or worker state as authoritative.
- Spring Boot handles request/response command workflows and transactional orchestration.
- Go workers handle asynchronous infrastructure workloads such as outbox publishing, Kafka consumption, reconciliation, and dead-letter replay.
- Kafka events should be derived from committed database state, usually through the transactional outbox pattern.
- Keep synchronous business writes and outbox writes in the same PostgreSQL transaction when outbox work begins.

## Repository Layout

```text
services/ledger-api/       Spring Boot API service
workers/                   Go worker module
workers/cmd/               Go executable entrypoints
workers/internal/          Shared Go worker packages
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
- Keep package names under `com.fanryan.ledgerflow`.
- Keep configuration in `src/main/resources/application.yml` unless a secret should come from the environment.
- Do not add JPA/Hibernate unless the project deliberately changes away from Spring Data JDBC.
- Use a global `@ControllerAdvice` for HTTP exception handling.
- Map API errors to the standard error response shape: `error_code`, `message`, `request_id`, and `timestamp`.

Useful commands:

```bash
cd services/ledger-api
gradle test
gradle bootRun
```

## Go Worker Conventions

- Work under `workers`.
- Keep executable entrypoints under `workers/cmd`.
- Keep shared worker code under `workers/internal`.
- Expected future entrypoints:
  - `workers/cmd/outbox-publisher`
  - `workers/cmd/transaction-consumer`
  - `workers/cmd/reconciliation-worker`
  - `workers/cmd/deadletter-replay-worker`
- Shared packages should be grouped by responsibility, such as config, db, kafka, logging, metrics, tracing, outbox, consumers, reconciliation, and deadletter.
- Prefer one Go module under `workers/` unless there is a strong reason to split worker modules.

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

## Security Rules

- `/health` is public.
- `/auth/login` is public.
- Application endpoints should require authentication by default.
- Use BCrypt for password verification. Never store raw passwords.
- JWT access tokens are short-lived API credentials.
- JWT refresh tokens are longer-lived credentials for token renewal.
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
- Ledger tests should cover balanced entries, idempotency, insufficient funds, currency mismatch, reversals, and concurrent transaction races.

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

## What Not To Do

- Do not modify application code when asked only for planning, documentation, or explanation.
- Do not bypass Flyway with manual schema changes except for temporary local debugging.
- Do not edit already-applied migrations without resetting the local database.
- Do not make Kafka or Redis the source of truth.
- Do not put Go worker entrypoints directly under `workers/internal`.
- Do not put shared Go code under `workers/cmd`.
- Do not make protected application endpoints public by default.
- Do not store raw passwords, JWT secrets, or production credentials in source control.
- Do not add accounts, transactions, ledger, outbox, Kafka workers, or reconciliation code before the current milestone calls for it.
