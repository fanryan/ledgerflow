package com.fanryan.ledgerflow.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConsumedLedgerEvent(
        UUID id,
        String eventType,
        UUID transactionId,
        String payload,
        OffsetDateTime consumedAt
) {
}