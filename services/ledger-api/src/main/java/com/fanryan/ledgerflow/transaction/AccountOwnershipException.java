package com.fanryan.ledgerflow.transaction;

public class AccountOwnershipException extends RuntimeException {

    public AccountOwnershipException() {
        super("Account does not belong to the authenticated user");
    }
}