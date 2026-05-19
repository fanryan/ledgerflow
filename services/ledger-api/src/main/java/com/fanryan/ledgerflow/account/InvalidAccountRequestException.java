package com.fanryan.ledgerflow.account;

public class InvalidAccountRequestException extends RuntimeException {

    public InvalidAccountRequestException(String message) {
        super(message);
    }
}