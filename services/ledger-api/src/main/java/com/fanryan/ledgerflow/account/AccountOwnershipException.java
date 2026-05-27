package com.fanryan.ledgerflow.account;

public class AccountOwnershipException extends RuntimeException {

    public AccountOwnershipException() {
        super("Account does not belong to the authenticated user");
    }
}