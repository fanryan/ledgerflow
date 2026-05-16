package com.fanryan.ledgerflow.auth;

public record LoginRequest(
        String email,
        String password
) {
}