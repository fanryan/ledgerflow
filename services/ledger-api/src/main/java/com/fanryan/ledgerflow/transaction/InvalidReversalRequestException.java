package com.fanryan.ledgerflow.transaction;

public class InvalidReversalRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidReversalRequestException(String message) {
        super(message);
    }
}