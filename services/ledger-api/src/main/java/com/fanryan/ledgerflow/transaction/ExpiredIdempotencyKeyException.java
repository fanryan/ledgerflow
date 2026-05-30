package com.fanryan.ledgerflow.transaction;

public class ExpiredIdempotencyKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExpiredIdempotencyKeyException() {
        super("Idempotency key has expired");
    }
}
