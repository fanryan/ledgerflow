package com.fanryan.ledgerflow.paycore;

public record PayCorePaymentSettledPayload(
        String eventId,
        String paymentId,
        String ownerUserId,
        String merchantAccountId,
        long amountMinor,
        String currency,
        String settledAt
) {
}