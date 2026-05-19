package com.fanryan.ledgerflow.account;

import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID ownerUserId,
        String currency,
        AccountStatus status,
        long balanceMinor,
        long version
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.id(),
                account.ownerUserId(),
                account.currency(),
                account.status(),
                account.balanceMinor(),
                account.version()
        );
    }
}