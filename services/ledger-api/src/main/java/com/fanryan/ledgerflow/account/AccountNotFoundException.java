package com.fanryan.ledgerflow.account;

public class AccountNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountNotFoundException() {
        super("Account not found");
    }
}
