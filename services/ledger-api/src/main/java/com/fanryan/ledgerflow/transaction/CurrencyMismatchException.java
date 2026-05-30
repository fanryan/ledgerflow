package com.fanryan.ledgerflow.transaction;

public class CurrencyMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException() {
        super("Currency must match account currency");
    }
}
