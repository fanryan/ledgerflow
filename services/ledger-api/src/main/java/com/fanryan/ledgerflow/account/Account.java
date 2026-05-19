package com.fanryan.ledgerflow.account;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("accounts")
public record Account(
        @Id UUID id,
        UUID ownerUserId,
        String currency,
        AccountStatus status,
        long balanceMinor,
        @Version long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}