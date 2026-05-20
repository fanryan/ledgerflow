package com.fanryan.ledgerflow.transaction;

public class InvalidTransactionRequestException extends RuntimeException {

    public InvalidTransactionRequestException(String message) {
        super(message);
    }
}