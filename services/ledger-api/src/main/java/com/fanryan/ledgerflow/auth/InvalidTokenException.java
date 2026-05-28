package com.fanryan.ledgerflow.auth;

public class InvalidTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidTokenException() {
        super("Invalid or expired token");
    }
}
