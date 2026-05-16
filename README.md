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

Current stage: **Milestone 0 - Repository Bootstrap**

Implemented:

- Repository structure
- Docker Compose infrastructure
- Spring Boot API skeleton
- `/health` endpoint

Next:

- PostgreSQL connection
- Flyway migration setup

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
