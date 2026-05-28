package com.fanryan.ledgerflow.account;

public class AccountOwnershipException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountOwnershipException() {
        super("Account does not belong to the authenticated user");
    }
}
