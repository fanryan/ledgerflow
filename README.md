# LedgerFlow

LedgerFlow is a production-inspired transaction processing and reconciliation platform built as a polyglot backend systems project.

The project uses:

- Spring Boot for the synchronous API layer
- Go for asynchronous infrastructure workers
- PostgreSQL as the source of truth
- Kafka for event-driven processing
- Docker Compose for local infrastructure

## Goals

LedgerFlow is designed to demonstrate:

- JWT authentication and role-based authorization
- Double-entry ledger accounting
- Idempotent transaction submission
- Optimistic concurrency control
- Transactional outbox publishing
- Retry-safe Kafka consumers
- Dead-letter recovery
- Reconciliation and auditability

## Architecture

```text
Client
  |
  v
Spring Boot Ledger API Service
  |
  v
PostgreSQL
  |
  v
Go Outbox Publisher
  |
  v
Kafka
  |
  +--> Go Transaction Consumer
  +--> Go Reconciliation Worker
  +--> Go Dead-Letter Replay Worker
```

## Request Workflow

Current implemented flow:

```text
Client
  |
  |  GET /health
  v
Spring Boot Ledger API
  |
  +--> returns {"status":"ok"}

Client
  |
  |  POST /auth/login
  |  email + password
  v
Spring Boot Ledger API
  |
  +--> Spring Security allows /auth/login
  |
  +--> AuthController
        |
        v
      AuthService
        |
        +--> UserRepository
        |     |
        |     v
        |   PostgreSQL users table
        |
        +--> BCrypt password check
        |
        +--> JwtService
              |
              v
            access token + refresh token

Client
  |
  |  GET /auth/me
  |  Authorization: Bearer <access_token>
  v
Spring Boot Ledger API
  |
  +--> JwtAuthenticationFilter
        |
        +--> validate JWT signature and expiry
        +--> read sub and role claims
        +--> populate Spring SecurityContext
        |
        v
      AuthController
        |
        v
      returns current user id + role
```

Planned transaction flow:

```text
Client
  |
  |  POST /transactions
  |  JWT + Idempotency-Key
  v
Spring Boot Ledger API
  |
  +--> authenticate JWT
  +--> validate request
  +--> enforce idempotency
  +--> apply optimistic concurrency checks
  +--> write transaction row
  +--> write balanced ledger entries
  +--> update account balances
  +--> write outbox event
  |
  v
PostgreSQL
  |
  v
Go Outbox Publisher
  |
  v
Kafka
  |
  +--> Go consumers
  +--> Reconciliation worker
  +--> Dead-letter replay worker
```

## Repository Structure

```text
ledgerflow/
  services/
    ledger-api/
      src/
        main/
          java/
            com/
              fanryan/
                ledgerflow/
          resources/
            db/
              migration/
        test/
          java/
            com/
              fanryan/
                ledgerflow/
      build.gradle

  workers/
    outbox-publisher/
    transaction-consumer/
    reconciliation-worker/
    deadletter-replay-worker/

  shared/
    schemas/

  infrastructure/
    docker/
    kafka/

  scripts/
  loadtests/
  docs/

  docker-compose.yml
  README.md
  .gitignore
```

## Current Status

Current stage: **Milestone 1 - API and Authentication Foundation**

Implemented:

- Repository structure
- Docker Compose infrastructure
- Spring Boot API skeleton
- `/health` endpoint
- PostgreSQL connection
- Flyway migration setup
- `users` table migration
- Seed admin user migration
- Spring Security baseline
- `/auth/login` endpoint
- Auth error handling for invalid credentials
- BCrypt password verification
- JWT access token generation
- JWT refresh token generation
- JWT validation filter
- `/auth/me` authenticated endpoint

Next:

- `/auth/refresh` endpoint
- Account table migration
- Account creation API

## Local Development

Start local infrastructure:

```bash
docker compose up -d
```

Stop local infrastructure:

```bash
docker compose down
```

Delete local infrastructure volumes:

```bash
docker compose down -v
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

Log in as the local admin user:

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

## Milestones

### Milestone 1

- Spring Boot API
- JWT authentication
- Account creation
- Transaction posting
- Double-entry ledger

### Milestone 2

- Idempotency
- Optimistic concurrency
- Reversal support
- Concurrent transaction tests

### Milestone 3

- Transactional outbox
- Kafka publishing
- Retry handling
- Dead-letter routing

### Milestone 4

- Go consumers
- Reconciliation worker
- Replay tooling

### Milestone 5

- Integration tests
- Benchmarks
- Observability
- Architecture documentation
