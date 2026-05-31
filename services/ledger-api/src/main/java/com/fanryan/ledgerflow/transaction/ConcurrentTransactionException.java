package com.fanryan.ledgerflow.transaction;

public class ConcurrentTransactionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentTransactionException() {
        super("Transaction could not be completed because the account was updated concurrently");
    }
}