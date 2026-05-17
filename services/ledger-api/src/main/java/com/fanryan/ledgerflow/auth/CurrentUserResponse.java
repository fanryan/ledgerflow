package com.fanryan.ledgerflow.auth;

import java.util.UUID;

public record CurrentUserResponse(
        UUID userId,
        String role
) {
}