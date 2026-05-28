package com.fanryan.ledgerflow.transaction;

public class InsufficientFundsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InsufficientFundsException() {
        super("Insufficient funds");
    }
}
