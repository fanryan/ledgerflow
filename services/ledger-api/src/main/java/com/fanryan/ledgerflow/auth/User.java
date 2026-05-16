package com.fanryan.ledgerflow.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("users")
public record User(
        @Id UUID id,
        String email,
        String passwordHash,
        UserRole role,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}