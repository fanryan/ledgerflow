package com.fanryan.ledgerflow.account;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(
            Authentication authentication,
            @RequestBody CreateAccountRequest request
    ) {
        UUID ownerUserId = (UUID) authentication.getPrincipal();

        return accountService.createAccount(ownerUserId, request);
    }

    @GetMapping("/accounts")
    public List<AccountResponse> listAccounts(Authentication authentication) {
        UUID ownerUserId = (UUID) authentication.getPrincipal();

        return accountService.listAccounts(ownerUserId);
    }
}