# PayCore Integration

This document explains the current PayCore integration in LedgerFlow. PayCore is the upstream payment system; LedgerFlow remains the ledger and reconciliation system.

The implemented slice consumes PayCore Kafka events and turns them into idempotent LedgerFlow transactions.

## 1. Current Scope

### Implemented

- Spring Kafka consumer package under `services/ledger-api/src/main/java/com/fanryan/ledgerflow/paycore/`.
- Consumer for Kafka topic `payment.captured`.
- Consumer for Kafka topic `payment.settled`.
- Payload records for captured and settled payment events.
- Event payload parsing with Jackson `ObjectMapper`.
- Basic payload validation.
- PayCore `eventId` used as the LedgerFlow `Idempotency-Key`.
- PayCore `ownerUserId` used as the LedgerFlow transaction owner.
- PayCore `merchantAccountId` used as the LedgerFlow account id.
- PayCore payment events submit LedgerFlow `DEPOSIT` transactions.
- Invalid PayCore events are stored in `dead_letter_events`.
- Dead-letter replay can route pending PayCore events back through the matching consumer.
- Unit tests for captured and settled event handling.
- Unit tests proving invalid captured and settled events are stored as dead letters.
- Testcontainers Kafka integration tests for `payment.captured` and `payment.settled`.
- Manual verification that captured and settled events are consumed and posted successfully.

### Not Implemented Yet

- External PayCore merchant id to LedgerFlow account mapping.
- Separate settlement-clearing account model.

## 2. Runtime Flow

```text
PayCore
  |
  | payment.captured / payment.settled
  v
Kafka
  |
  v
PayCoreConsumer
  |
  | parse + validate event payload
  v
TransactionService.submitTransaction(...)
  |
  | eventId as idempotency key
  v
LedgerFlow transaction posting
  |
  +--> transactions
  +--> ledger_entries
  +--> accounts.balance_minor
  +--> idempotency_keys
  +--> outbox_events
```

The important design choice is that PayCore events enter LedgerFlow through the same transaction service used by the HTTP API. That means PayCore ingestion reuses the existing ownership checks, currency validation, idempotency rules, ledger posting rules, optimistic locking, and outbox event creation.

Invalid PayCore events follow the dead-letter path:

```text
PayCore event payload
  |
  v
PayCoreConsumer
  |
  | parse or validation failure
  v
dead_letter_events row with status PENDING
  |
  | POST /admin/dead-letter/replay
  v
DeadLetterReplayService
  |
  +--> consumePaymentCaptured(...)
  +--> consumePaymentSettled(...)
  |
  v
dead_letter_events row marked REPLAYED
```

## 3. Topics

### `payment.captured`

Handled by:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/paycore/PayCoreConsumer.java
```

Expected payload shape:

```json
{
  "eventId": "evt_capture_001",
  "paymentId": "pay_001",
  "ownerUserId": "00000000-0000-0000-0000-000000000001",
  "merchantAccountId": "5e824b14-77a3-4db7-882b-4c06abc2dc8b",
  "amountMinor": 2500,
  "currency": "USD",
  "capturedAt": "2026-06-05T10:00:00Z"
}
```

Current LedgerFlow behavior:

```text
eventId           -> idempotency key
ownerUserId       -> transaction owner user id
merchantAccountId -> account id
amountMinor       -> transaction amount
currency          -> transaction currency
type              -> DEPOSIT
description       -> PayCore payment captured: <paymentId>
```

### `payment.settled`

Handled by the same consumer class.

Expected payload shape:

```json
{
  "eventId": "evt_settle_001",
  "paymentId": "pay_001",
  "ownerUserId": "00000000-0000-0000-0000-000000000001",
  "merchantAccountId": "5e824b14-77a3-4db7-882b-4c06abc2dc8b",
  "amountMinor": 2500,
  "currency": "USD",
  "settledAt": "2026-06-05T10:05:00Z"
}
```

Current LedgerFlow behavior:

```text
eventId           -> idempotency key
ownerUserId       -> transaction owner user id
merchantAccountId -> account id
amountMinor       -> transaction amount
currency          -> transaction currency
type              -> DEPOSIT
description       -> PayCore payment settled: <paymentId>
```

## 4. Idempotency

PayCore event ingestion is idempotent because the consumer passes `eventId` into `TransactionService.submitTransaction(...)` as the idempotency key.

That gives PayCore events the same guarantees as HTTP transaction submissions:

- same event id and same payload replays the existing transaction result
- same event id with different transaction payload is rejected as an idempotency conflict
- retries do not post duplicate ledger entries
- retries do not update account balances twice

## 5. Validation

`PayCoreConsumer` validates the common required fields before submitting to the transaction service:

- `eventId` must be present
- `paymentId` must be present
- `ownerUserId` must be present
- `merchantAccountId` must be present
- `amountMinor` must be greater than zero
- `currency` must be an uppercase 3-letter code

The transaction service then applies LedgerFlow's normal domain checks, including account existence, ownership, account state, currency match, idempotency conflict detection, and optimistic locking.

## 6. Dead-Letter Handling

If parsing or validation fails, `PayCoreConsumer` saves the raw payload and error message to `dead_letter_events`.

The current dead-letter fields are:

- `source_topic`: the Kafka topic that produced the failed event.
- `event_key`: optional Kafka/event key. The current consumer stores `null`.
- `payload`: raw event payload as PostgreSQL `jsonb`.
- `error_message`: failure reason.
- `status`: `PENDING` or `REPLAYED`.
- `attempts`: replay count.
- `created_at`: when the dead-letter row was created.
- `replayed_at`: when replay marked the row as replayed.

Replay is exposed through:

```text
POST /admin/dead-letter/replay?limit=10
```

The endpoint is authenticated by default. It replays pending PayCore dead letters by calling the matching consumer method based on `source_topic`.

## 7. File-by-File

### `PayCoreConsumer.java`

Kafka listener component for PayCore events.

It listens to:

```text
payment.captured
payment.settled
```

It parses each JSON payload, validates required fields, and submits a LedgerFlow deposit using `TransactionService`.

Invalid events are saved through `DeadLetterEventRepository`.

### `PayCorePaymentCapturedPayload.java`

Record representing the JSON payload for `payment.captured`.

### `PayCorePaymentSettledPayload.java`

Record representing the JSON payload for `payment.settled`.

### `PayCoreConsumerTest.java`

Unit tests proving that both captured and settled events call `TransactionService.submitTransaction(...)` with the PayCore `eventId` as the idempotency key. The same test class also verifies invalid events are saved as dead letters and do not call the transaction service.

### `DeadLetterEventRepository.java`

Explicit JDBC repository for `dead_letter_events`.

It stores raw JSONB payloads, finds pending rows, and marks replayed rows as `REPLAYED`.

### `DeadLetterReplayService.java`

Loads pending dead-letter rows and routes each one back to the correct PayCore consumer method.

### `DeadLetterController.java`

Defines:

```text
POST /admin/dead-letter/replay
```

### `application.yml`

Defines the PayCore consumer group id:

```yaml
paycore:
  kafka:
    consumer-group-id: paycore-consumer
```

The test profile uses a separate consumer group id.

## 8. Design Notes

PayCore integration currently chooses the simplest useful ledger behavior: both captured and settled events post deposits into the merchant account referenced by `merchantAccountId`.

That is enough to prove the important backend patterns:

- Kafka event ingestion
- event-driven transaction submission
- durable idempotency
- double-entry ledger posting
- retry-safe consumer behavior
- downstream outbox event creation after ledger posting
- dead-letter persistence for invalid events
- admin-triggered dead-letter replay

Future work can add richer settlement modeling, external id mapping, and scheduled replay without changing the core rule that PostgreSQL remains the source of truth.

## 9. Interview Questions

1. **Why use the PayCore event id as the idempotency key?**  
   Because Kafka can redeliver events. The event id gives LedgerFlow a stable key to detect retries and avoid duplicate balance movement.

2. **Why call `TransactionService` instead of writing ledger rows directly in the consumer?**  
   The service already owns validation, idempotency, ledger posting, balance updates, optimistic locking, and outbox creation.

3. **What happens if the same PayCore event is consumed twice?**  
   The second consume reuses the same idempotency key and returns the existing transaction behavior instead of creating another transaction.

4. **What happens if PayCore sends the same event id with different data?**  
   LedgerFlow detects a request hash mismatch and rejects it as an idempotency conflict.

5. **Why is this consumer still not a full production integration?**  
   It does not yet include external id mapping, richer settlement accounting, or scheduled replay.

6. **What happens when a PayCore event is invalid?**  
   The consumer stores the raw payload and error message in `dead_letter_events` with status `PENDING`.

7. **How does dead-letter replay know which consumer method to call?**  
   `DeadLetterReplayService` routes by `source_topic`: `payment.captured` calls `consumePaymentCaptured`, and `payment.settled` calls `consumePaymentSettled`.

## 10. Checklist Before Moving On

- [ ] Explain what PayCore is relative to LedgerFlow.
- [ ] Explain which Kafka topics LedgerFlow consumes from PayCore.
- [ ] Explain how a PayCore event becomes a LedgerFlow transaction.
- [ ] Explain why `eventId` is the idempotency key.
- [ ] Explain why duplicate Kafka delivery does not create duplicate balance movement.
- [ ] Explain why the consumer calls `TransactionService`.
- [ ] Explain how invalid PayCore events become dead-letter rows.
- [ ] Explain how pending dead-letter rows are replayed.
- [ ] Explain what the Testcontainers Kafka tests prove.
- [ ] Explain what is still planned: external id mapping, richer settlement accounting, and scheduled replay.
