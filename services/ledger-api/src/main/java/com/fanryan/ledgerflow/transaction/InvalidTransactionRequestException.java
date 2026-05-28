package com.fanryan.ledgerflow.transaction;

public class InvalidTransactionRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidTransactionRequestException(String message) {
        super(message);
    }
}
