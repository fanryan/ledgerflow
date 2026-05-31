package com.fanryan.ledgerflow.account;

public class InvalidAccountStateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidAccountStateException(String message) {
        super(message);
    }
}