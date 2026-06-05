# Testing

LedgerFlow uses a layered test strategy: fast unit tests for local behavior, Spring flow tests for HTTP/security paths, and Testcontainers integration tests for real PostgreSQL and Kafka behavior.

## Current Coverage

Implemented test layers:

- Auth flow tests for login, refresh, invalid credentials, invalid tokens, and `/auth/me`.
- Account flow tests for authenticated creation, listing, validation, and ledger entry listing.
- Transaction flow tests for idempotency, ownership, validation, ledger posting, reversals, insufficient funds, and concurrency conflicts.
- Outbox repository and publisher service tests.
- Kafka consumer unit tests for internal ledger events and PayCore events.
- Reconciliation repository, service, and flow tests.
- Dead-letter repository, replay service, and replay flow tests.
- Testcontainers PostgreSQL and Kafka integration tests.

## Testcontainers

The reusable base class is:

```text
services/ledger-api/src/test/java/com/fanryan/ledgerflow/support/IntegrationTestSupport.java
```

It starts:

- PostgreSQL: `postgres:16-alpine`
- Kafka: `apache/kafka-native:3.8.0`

It dynamically wires container values into Spring:

- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`
- `spring.kafka.bootstrap-servers`

The outbox scheduler is disabled during tests so integration tests can call the publisher explicitly.

## Integration Tests

### `IntegrationTestSupportSmokeTest`

Verifies the Spring context starts with containerized PostgreSQL and Kafka.

### `PostgresMigrationIntegrationTest`

Verifies Flyway applies the real LedgerFlow schema to containerized PostgreSQL.

Tables checked include:

- `users`
- `accounts`
- `transactions`
- `ledger_entries`
- `idempotency_keys`
- `outbox_events`
- `consumed_ledger_events`
- `reconciliation_reports`
- `dead_letter_events`
- `flyway_schema_history`

### `OutboxPublisherKafkaIntegrationTest`

Writes a pending outbox row to real PostgreSQL, runs the outbox publisher, and consumes the resulting `ledger.events` message from real Kafka.

### `TransactionOutboxIntegrationTest`

Posts a real LedgerFlow transaction through `TransactionService`, verifies it reaches `POSTED`, publishes the resulting outbox event, and consumes the matching Kafka payload.

This proves the core chain:

```text
posted transaction -> outbox row -> outbox publisher -> Kafka ledger.events
```

### `PayCoreKafkaIntegrationTest`

Publishes real Kafka messages to:

- `payment.captured`
- `payment.settled`

Then verifies the Spring Kafka listener creates LedgerFlow transactions in PostgreSQL using the PayCore `eventId` as the idempotency key.

## Commands

Run the full Spring test suite:

```bash
cd services/ledger-api
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home gradle test
```

Run only Testcontainers support tests:

```bash
cd services/ledger-api
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home gradle test --tests 'com.fanryan.ledgerflow.support.*'
```

Run PayCore Kafka integration tests:

```bash
cd services/ledger-api
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home gradle test --tests com.fanryan.ledgerflow.paycore.PayCoreKafkaIntegrationTest
```

## Design Notes

Testcontainers gives the resume claims more weight because the tests run against real PostgreSQL migrations and real Kafka publish/consume behavior.

The integration tests still keep scope focused. They do not replace unit and flow tests; they prove the infrastructure boundaries where mocks would be weakest.

## Checklist

- [ ] Explain why PostgreSQL integration tests are stronger than H2 tests for this project.
- [ ] Explain what the Flyway migration integration test proves.
- [ ] Explain what the outbox Kafka integration tests prove.
- [ ] Explain what the PayCore Kafka integration tests prove.
- [ ] Explain why the outbox scheduler is disabled in tests.
- [ ] Explain why Kafka consumers are enabled for PayCore integration tests.
