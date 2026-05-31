package com.fanryan.ledgerflow.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("outbox_events")
public record OutboxEvent(
        @Id
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        OutboxEventStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String claimedBy,
        OffsetDateTime lockedUntil,
        OffsetDateTime publishedAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}