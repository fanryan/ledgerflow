package com.fanryan.ledgerflow.account;

public class InvalidAccountRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidAccountRequestException(String message) {
        super(message);
    }
}
