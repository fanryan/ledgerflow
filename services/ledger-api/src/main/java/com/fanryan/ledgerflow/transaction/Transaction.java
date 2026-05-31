package com.fanryan.ledgerflow.transaction;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("transactions")
public record Transaction(
        @Id
        UUID id,
        UUID accountId,
        UUID ownerUserId,
        String idempotencyKey,
        TransactionType type,
        long amountMinor,
        String currency,
        TransactionStatus status,
        String description,
        UUID reversalOfTransactionId,
        OffsetDateTime reversedAt,
        @Version
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}