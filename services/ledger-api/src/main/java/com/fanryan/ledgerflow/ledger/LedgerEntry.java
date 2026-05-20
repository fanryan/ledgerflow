package com.fanryan.ledgerflow.ledger;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("ledger_entries")
public record LedgerEntry(
        @Id UUID id,
        UUID transactionId,
        UUID accountId,
        LedgerEntryDirection direction,
        long amountMinor,
        String currency,
        OffsetDateTime createdAt
) {
}