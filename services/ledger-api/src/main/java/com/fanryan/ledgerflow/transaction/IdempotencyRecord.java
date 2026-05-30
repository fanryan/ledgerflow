package com.fanryan.ledgerflow.transaction;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IdempotencyRecord(
        String key,
        UUID ownerUserId,
        String requestHash,
        UUID transactionId,
        String responseStatus,
        String responseBody,
        OffsetDateTime expiresAt
) {
}
