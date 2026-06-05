package com.fanryan.ledgerflow.deadletter;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeadLetterEvent(
        UUID id,
        String sourceTopic,
        String eventKey,
        String payload,
        String errorMessage,
        DeadLetterEventStatus status,
        long attempts,
        OffsetDateTime createdAt,
        OffsetDateTime replayedAt
) {
}