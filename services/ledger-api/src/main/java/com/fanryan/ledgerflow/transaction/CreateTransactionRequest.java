package com.fanryan.ledgerflow.transaction;

import java.util.UUID;

public record CreateTransactionRequest(
        UUID accountId,
        TransactionType type,
        long amountMinor,
        String currency,
        String description
) {
}