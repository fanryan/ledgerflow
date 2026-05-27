package com.fanryan.ledgerflow.account;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("Account not found");
    }
}