# Dead-Letter Replay

This document explains the current dead-letter replay foundation in LedgerFlow. The implemented slice focuses on PayCore events that fail parsing or validation.

## 1. Current Scope

### Implemented

- `dead_letter_events` table through `V12__create_dead_letter_events_table.sql`.
- Dead-letter event model and status enum.
- Explicit JDBC repository for JSONB payload storage.
- Invalid PayCore `payment.captured` events are persisted as dead letters.
- Invalid PayCore `payment.settled` events are persisted as dead letters.
- Authenticated admin endpoint:

```text
POST /admin/dead-letter/replay?limit=10
```

- Replay service routes pending PayCore dead letters back through `PayCoreConsumer`.
- Replayed rows are marked `REPLAYED`.
- Repository, replay service, and HTTP flow tests.

### Not Implemented Yet

- Scheduled replay.
- Fine-grained admin authorization beyond normal authentication.
- Dead-letter list/detail endpoints.
- Replay of non-PayCore topics.
- Max-attempt limits and terminal failed-replay state.
- Testcontainers Kafka integration test for replay against a running broker.

## 2. Runtime Flow

```text
PayCore Kafka event
  |
  v
PayCoreConsumer
  |
  | parse/validation failure
  v
dead_letter_events
  |
  | status = PENDING
  v
POST /admin/dead-letter/replay
  |
  v
DeadLetterReplayService
  |
  +--> payment.captured -> consumePaymentCaptured(payload)
  +--> payment.settled  -> consumePaymentSettled(payload)
  |
  v
dead_letter_events.status = REPLAYED
```

The replay path intentionally calls the original consumer methods. That keeps replay behavior aligned with normal ingestion: if an event is repaired or becomes valid later, it goes through the same validation, transaction submission, idempotency, ledger posting, and outbox creation path.

## 3. Table

`dead_letter_events` stores:

- `id`: primary key.
- `source_topic`: original topic, such as `payment.captured`.
- `event_key`: optional event key. Current PayCore consumer stores `null`.
- `payload`: raw event payload as PostgreSQL `jsonb`.
- `error_message`: failure reason.
- `status`: `PENDING` or `REPLAYED`.
- `attempts`: replay count.
- `created_at`: when the dead-letter row was created.
- `replayed_at`: when replay marked the row as replayed.

Indexes:

```text
idx_dead_letter_events_status_created_at
idx_dead_letter_events_source_topic
```

## 4. Endpoint

```http
POST /admin/dead-letter/replay?limit=10
Authorization: Bearer <access_token>
```

Response:

```json
{
  "replayed": 1
}
```

The endpoint returns `202 Accepted` because replay is operational work. It is protected by default through Spring Security.

## 5. File-by-File

### `DeadLetterEvent.java`

Record representing one row in `dead_letter_events`.

### `DeadLetterEventStatus.java`

Current statuses:

```text
PENDING
REPLAYED
```

### `DeadLetterEventRepository.java`

Explicit JDBC repository for the dead-letter table.

It uses explicit SQL because:

- `payload` is PostgreSQL `jsonb`
- pending replay queries are operational SQL
- replay state updates are explicit

Current methods:

- `save(...)`
- `findPending(...)`
- `findById(...)`
- `markReplayed(...)`

### `DeadLetterReplayService.java`

Loads pending rows and routes by `source_topic`.

Current routing:

```text
payment.captured -> PayCoreConsumer.consumePaymentCaptured(...)
payment.settled  -> PayCoreConsumer.consumePaymentSettled(...)
```

Unsupported source topics throw `IllegalArgumentException`.

### `DeadLetterController.java`

Defines the admin replay endpoint.

### `DeadLetterReplayResponse.java`

Response DTO containing the number of rows replayed.

### `PayCoreConsumer.java`

Saves invalid PayCore events to the dead-letter repository instead of losing the failed payload.

## 6. Design Decisions

Dead-letter rows are stored in PostgreSQL because PostgreSQL is LedgerFlow's source of truth for operational state. Kafka delivery is not the authoritative record of what still needs operator attention.

Replay is explicit instead of automatic. That makes failures auditable and keeps potentially unsafe retries under operator control.

Replay calls the existing consumer methods instead of duplicating ingestion logic. This avoids a second code path that could bypass idempotency or ledger rules.

## 7. Interview Questions

1. **Why have a dead-letter table?**  
   To preserve failed event payloads and error messages so they can be inspected and replayed instead of being lost in logs.

2. **Why store the payload as JSONB?**  
   It keeps the raw event structured and queryable while preserving the original payload.

3. **Why is replay an admin endpoint?**  
   Replay is operational tooling that can cause side effects, so it should not be exposed as a normal user workflow.

4. **Why replay through the original consumer?**  
   It reuses validation, idempotency, transaction posting, and ledger rules.

5. **How does replay avoid double posting?**  
   PayCore events still use `eventId` as the transaction idempotency key, so replaying the same logical event cannot move balances twice.

6. **What does `REPLAYED` mean?**  
   LedgerFlow attempted the replay path and marked the row out of the pending queue.

## 8. Checklist Before Moving On

- [ ] Explain when a PayCore event becomes a dead-letter row.
- [ ] Explain what `dead_letter_events` stores.
- [ ] Explain why PostgreSQL stores dead-letter state.
- [ ] Explain how replay routes by `source_topic`.
- [ ] Explain why replay calls `PayCoreConsumer`.
- [ ] Explain why PayCore idempotency still protects replay.
- [ ] Explain what is not implemented yet: scheduled replay, list/detail endpoints, max attempts, and Testcontainers Kafka replay tests.
