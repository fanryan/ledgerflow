package com.fanryan.ledgerflow.ledger;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID transactionId,
        UUID accountId,
        LedgerEntryDirection direction,
        long amountMinor,
        String currency,
        OffsetDateTime createdAt
) {

    public static LedgerEntryResponse from(LedgerEntry ledgerEntry) {
        return new LedgerEntryResponse(
                ledgerEntry.id(),
                ledgerEntry.transactionId(),
                ledgerEntry.accountId(),
                ledgerEntry.direction(),
                ledgerEntry.amountMinor(),
                ledgerEntry.currency(),
                ledgerEntry.createdAt()
        );
    }
}