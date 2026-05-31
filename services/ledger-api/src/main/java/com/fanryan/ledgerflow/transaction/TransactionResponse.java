package com.fanryan.ledgerflow.transaction;

import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        UUID ownerUserId,
        String idempotencyKey,
        TransactionType type,
        long amountMinor,
        String currency,
        TransactionStatus status,
        String description,
        UUID reversalOfTransactionId
) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.accountId(),
                transaction.ownerUserId(),
                transaction.idempotencyKey(),
                transaction.type(),
                transaction.amountMinor(),
                transaction.currency(),
                transaction.status(),
                transaction.description(),
                transaction.reversalOfTransactionId()
        );
    }
}