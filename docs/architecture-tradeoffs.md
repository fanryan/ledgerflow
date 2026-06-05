# Architecture Tradeoffs

This document captures LedgerFlow's cross-cutting architecture decisions. It is written for interview review: each section explains the decision, the alternative, why the current choice fits, the tradeoff, and a concise way to talk about it.

## Decision Index

| Area | Decision |
| --- | --- |
| Persistence | Spring Data JDBC plus explicit SQL instead of JPA/Hibernate |
| State ownership | PostgreSQL is the source of truth, Kafka is propagation |
| Event publishing | Transactional outbox instead of direct dual writes |
| Outbox scaling | Claim-based publishing with `FOR UPDATE SKIP LOCKED` and `locked_until` |
| Idempotency | Idempotency keys plus request hashes |
| Concurrency | Optimistic locking for account balance updates |
| Ledger model | Double-entry ledger entries instead of balance-only updates |
| Reversals | Append offsetting reversal transactions instead of mutating history |
| Auth | Stateless JWT API instead of server sessions |
| Failure handling | Explicit dead-letter replay instead of infinite retry |
| Testing | Testcontainers instead of only mocks or H2 |
| Build tool | Gradle, but this is not a core architecture decision |

## 1. Persistence: JDBC And Explicit SQL

**Decision**

LedgerFlow uses Spring Data JDBC for simple row-mapped repositories and `NamedParameterJdbcTemplate` for SQL-heavy infrastructure repositories.

It does not use JPA/Hibernate.

**Where Spring Data JDBC Fits**

Spring Data JDBC is used where persistence maps naturally to table rows:

- `UserRepository`
- `AccountRepository`
- `TransactionRepository`
- `LedgerEntryRepository`

This works well for primary-key lookups, derived finder methods, straightforward inserts and updates, `@Version` optimistic locking, and models that stay close to the schema.

**Where Explicit SQL Fits**

`NamedParameterJdbcTemplate` is used when SQL behavior is part of the feature:

- `IdempotencyRepository`
- `OutboxEventRepository`
- `ConsumedLedgerEventRepository`
- `ReconciliationReportRepository`
- `DeadLetterEventRepository`

These repositories need precise control over PostgreSQL `jsonb`, `ON CONFLICT DO NOTHING`, `FOR UPDATE SKIP LOCKED`, report queries, and operational state transitions.

**Alternative**

Use JPA/Hibernate for most persistence.

**Why LedgerFlow Chose This**

LedgerFlow is command-oriented and ledger-oriented. Most operations need predictable SQL, explicit transaction boundaries, and auditable row changes. The project does not need lazy-loaded object graphs, dirty checking, or ORM-managed relationships.

**Tradeoff**

Benefits:

- SQL is visible and reviewable.
- PostgreSQL-specific behavior is explicit.
- Concurrency and outbox semantics are easier to reason about.

Costs:

- More SQL and mapping code are written manually.
- Complex read models may need more boilerplate.

**Interview Framing**

> LedgerFlow uses Spring Data JDBC for simple aggregates and explicit JDBC where SQL semantics are central: idempotency, JSONB payloads, outbox claiming, consumer dedupe, reconciliation, and dead-letter replay. I avoided JPA because predictable SQL and transaction boundaries matter more here than ORM object graph management.

## 2. Source Of Truth: PostgreSQL Over Kafka

**Decision**

PostgreSQL owns the committed business state.

Kafka is used for asynchronous propagation, not as the authoritative ledger.

**Alternative**

Use Kafka as an event-sourced log and rebuild all state from events.

**Why LedgerFlow Chose This**

Money movement needs a durable, queryable, transactional state store for balances, transactions, ledger entries, idempotency decisions, outbox rows, reconciliation reports, and dead-letter records.

PostgreSQL gives the project constraints, indexes, transactions, and row-level concurrency in one consistency boundary.

**Tradeoff**

Benefits:

- Direct queries are simple.
- Financial state is auditable in one database.
- Transactional writes can include business rows and outbox rows together.

Costs:

- Kafka consumers are eventually consistent.
- Event replay is not the primary state reconstruction model.

**Interview Framing**

> Kafka propagates facts after commit, but PostgreSQL owns the committed ledger state. That keeps transaction posting, idempotency, outbox writes, and reconciliation inside one authoritative consistency boundary.

## 3. Event Publishing: Transactional Outbox

**Decision**

LedgerFlow writes outbox rows in the same PostgreSQL transaction as business changes, then publishes those rows to Kafka asynchronously.

**Alternative**

Write to PostgreSQL and publish to Kafka directly in the request path.

**Why LedgerFlow Chose This**

Direct dual writes can split:

```text
database commit succeeds, Kafka publish fails
Kafka publish succeeds, database transaction rolls back
```

The outbox pattern makes the durable state explicit:

```text
database transaction:
  - transaction row
  - ledger entries
  - account balance update
  - outbox event row

publisher:
  - claim outbox row
  - publish to Kafka
  - mark published or failed
```

**Tradeoff**

Benefits:

- Avoids dual-write inconsistency.
- Gives operational visibility into event publishing state.
- Supports retry after broker failures.

Costs:

- Kafka consumers observe events eventually, not synchronously.
- A publisher component and retry logic are required.

**Interview Framing**

> I used a transactional outbox so the database commit and the durable event-to-publish record are atomic. Kafka publishing is retried from the outbox instead of being a fragile second write inside the request.

## 4. Outbox Scaling: Claim-Based Publishing

**Decision**

Outbox publishers claim rows with SQL-level locking and lock expiry.

Key mechanisms:

- `FOR UPDATE SKIP LOCKED`
- `claimed_by`
- `locked_until`
- `PENDING -> PROCESSING -> PUBLISHED/FAILED`

**Alternative**

Run only one publisher, or use a simple `claimed_at` timestamp.

**Why LedgerFlow Chose This**

Multiple LedgerFlow instances should be able to publish safely. `FOR UPDATE SKIP LOCKED` lets instances claim different rows without blocking each other. `locked_until` lets stale claims recover naturally after a publisher crash.

**Tradeoff**

Benefits:

- Horizontally safer outbox publishing.
- Crash recovery is straightforward.
- Stale claim queries are simple.

Costs:

- More state fields and more complex SQL.
- Publishing is still at-least-once, so consumers must be idempotent.

**Interview Framing**

> The publisher is safe for multiple instances because rows are claimed with row-level locks and lock expiry. It gives at-least-once delivery, so consumers still need deduplication.

## 5. Idempotency: Keys Plus Request Hashes

**Decision**

LedgerFlow stores idempotency keys with a request hash and response metadata.

**Alternative**

Only put a unique constraint on `idempotency_key`.

**Why LedgerFlow Chose This**

The request hash distinguishes a true retry from key misuse:

```text
same key + same payload      -> replay original result
same key + different payload -> reject with 409
```

Without the hash, a client could reuse a key for a different request and receive a misleading old result.

**Tradeoff**

Benefits:

- Prevents duplicate balance movement.
- Detects idempotency key misuse.
- Works for API retries and Kafka redelivery.

Costs:

- Requires stable request hashing.
- Stores more metadata.

**Interview Framing**

> Idempotency is not just a unique key. LedgerFlow stores a request hash so identical retries replay, but same-key payload mismatches are rejected as conflicts.

## 6. Concurrency: Optimistic Locking

**Decision**

Account balance updates use optimistic locking with an account version field.

**Alternative**

Use pessimistic locks such as `SELECT ... FOR UPDATE`.

**Why LedgerFlow Chose This**

Optimistic locking keeps normal request flow simple and detects races explicitly. If two withdrawals race, one update wins and the stale writer returns `409 CONCURRENT_TRANSACTION_CONFLICT`.

**Tradeoff**

Benefits:

- Avoids long-held application-level database locks.
- Makes stale writes visible.
- Works well when account contention is moderate.

Costs:

- Hot accounts can see more conflicts.
- Clients or services need retry/reload behavior.

**When This Might Change**

Pessimistic locking may fit better for high-contention settlement accounts or batch workflows where waiting is cheaper than retrying.

**Interview Framing**

> I chose optimistic locking because account balance races should be detected explicitly. The system prevents overdrafts by letting one write win and returning a conflict for stale writes.

## 7. Ledger Model: Double-Entry Entries

**Decision**

LedgerFlow stores balanced ledger entries for posted transactions.

**Alternative**

Only update account balances.

**Why LedgerFlow Chose This**

Balance-only systems are simpler, but they hide why the balance changed. Ledger entries preserve an audit trail and support the invariant:

```text
total debits == total credits
```

Current entries balance between the user account and a seeded USD settlement system account.

**Tradeoff**

Benefits:

- Balances are explainable.
- Reconciliation can detect both debit/credit imbalance and stored-balance drift.
- Reversals can be modeled cleanly.

Costs:

- More rows per transaction.
- Requires system accounts and reconciliation logic.

**Interview Framing**

> The account balance is a derived operational value, but ledger entries explain the movement. Double-entry gives reconciliation invariants that balance-only updates do not: entries must balance, and stored user balances can be checked against ledger-derived balances.

## 8. Reversals: Append Instead Of Mutate

**Decision**

LedgerFlow creates reversal transactions instead of editing or deleting posted transactions.

**Alternative**

Mutate the original transaction or delete its effects.

**Why LedgerFlow Chose This**

Financial systems need history. A reversal is a new business event:

```text
original transaction: POSTED
reversal transaction: POSTED, reversal_of_transaction_id = original id
original transaction: reversed_at populated
```

**Tradeoff**

Benefits:

- Preserves audit history.
- Keeps ledger entries append-style.
- Makes reversal reason and timing explicit.

Costs:

- Queries must understand reversal relationships.
- Double reversal must be guarded.

**Interview Framing**

> Reversal is not deletion. LedgerFlow appends an offsetting transaction and marks the original as reversed, preserving the audit trail.

## 9. Auth: Stateless JWT API

**Decision**

LedgerFlow uses stateless JWT access and refresh tokens.

It disables CSRF, form login, HTTP basic, and server-side sessions.

**Alternative**

Use server-side sessions.

**Why LedgerFlow Chose This**

The API is JSON-based and backend-oriented. Clients pass `Authorization: Bearer <token>`, and horizontal scaling is simpler when server memory does not hold session state.

**Tradeoff**

Benefits:

- Stateless API nodes.
- Simple bearer-token auth for API clients.
- Works naturally with non-browser clients.

Costs:

- Revocation and refresh-token rotation need additional storage if added.
- JWT claims must stay minimal and be trusted only after signature and expiry validation.

**Interview Framing**

> JWT keeps the API stateless and easy to scale. The tradeoff is that revocation and refresh-token rotation require extra storage, which is intentionally future work here.

## 10. Failure Handling: Explicit Dead-Letter Replay

**Decision**

Invalid PayCore events are stored in `dead_letter_events` and replayed through an authenticated admin endpoint.

**Alternative**

Retry forever in the Kafka listener.

**Why LedgerFlow Chose This**

Bad payloads usually do not become valid through repetition. Infinite retry can hide poison messages and create noisy consumers. Dead-letter persistence keeps the raw payload and error message available for inspection.

Replay calls the original consumer method, so replay still uses validation, idempotency, and ledger rules.

**Tradeoff**

Benefits:

- Failed payloads are durable and auditable.
- Operators can replay intentionally.
- Replay uses the same ingestion path as normal consumption.

Costs:

- Replay is manual today.
- List/detail tooling and max-attempt states are future work.

**Interview Framing**

> Failed PayCore events are stored for inspection and explicit replay. I do not retry poison messages forever; replay goes back through the original consumer so idempotency and ledger rules still apply.

## 11. Testing: Testcontainers

**Decision**

LedgerFlow uses Testcontainers for PostgreSQL and Kafka integration tests.

**Alternative**

Use only mocks, or use H2 for database tests.

**Why LedgerFlow Chose This**

PostgreSQL-specific behavior matters:

- `jsonb`
- Flyway migrations
- SQL constraints
- outbox and dead-letter table behavior

Kafka publish/consume behavior also sits at an infrastructure boundary that mocks cannot fully prove.

**Tradeoff**

Benefits:

- Tests validate real PostgreSQL and Kafka behavior.
- Resume claims about outbox and Kafka are more credible.
- Migration problems are caught against the real database engine.

Costs:

- Tests are slower.
- Docker must be available.
- Container lifecycle needs care.

**Interview Framing**

> I still use fast unit and flow tests, but Testcontainers proves the infrastructure claims: real Flyway migrations, real PostgreSQL JSONB behavior, and real Kafka publish/consume paths.

## 12. Build Tool: Gradle

**Decision**

LedgerFlow uses Gradle.

**Alternative**

Use Maven.

**Why This Is Lower Signal**

Gradle is common in Spring Boot projects and works well for dependency management and test configuration. Maven would also be valid. Nothing in LedgerFlow's architecture depends deeply on Gradle-specific behavior.

**Interview Framing**

> I would not over-index on Gradle versus Maven for this project. The important build decision is reproducible tests and real integration coverage through Testcontainers.
