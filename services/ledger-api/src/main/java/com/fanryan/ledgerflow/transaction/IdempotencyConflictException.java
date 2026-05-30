package com.fanryan.ledgerflow.transaction;

public class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException() {
        super("Idempotency key was already used with a different request payload");
    }
}
