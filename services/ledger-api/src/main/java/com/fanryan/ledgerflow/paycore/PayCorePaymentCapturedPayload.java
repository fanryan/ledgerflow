package com.fanryan.ledgerflow.paycore;

public record PayCorePaymentCapturedPayload(
        String eventId,
        String paymentId,
        String ownerUserId,
        String merchantAccountId,
        long amountMinor,
        String currency,
        String capturedAt
) {
}