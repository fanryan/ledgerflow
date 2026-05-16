package com.fanryan.ledgerflow.auth;

public record LoginResponse(
        String accessToken,
        String refreshToken
) {
}