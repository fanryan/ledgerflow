package com.fanryan.ledgerflow.transaction;

import java.util.UUID;

public record TransactionPostedEventPayload(
        UUID transactionId,
        UUID accountId,
        UUID ownerUserId,
        String idempotencyKey,
        TransactionType type,
        long amountMinor,
        String currency,
        TransactionStatus status,
        UUID reversalOfTransactionId
) {

    public static TransactionPostedEventPayload from(Transaction transaction) {
        return new TransactionPostedEventPayload(
                transaction.id(),
                transaction.accountId(),
                transaction.ownerUserId(),
                transaction.idempotencyKey(),
                transaction.type(),
                transaction.amountMinor(),
                transaction.currency(),
                transaction.status(),
                transaction.reversalOfTransactionId()
        );
    }
}