package com.fanryan.ledgerflow.transaction;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse submitTransaction(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateTransactionRequest request
    ) {
        UUID ownerUserId = (UUID) authentication.getPrincipal();

        return transactionService.submitTransaction(
                ownerUserId,
                idempotencyKey,
                request
        );
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> listTransactions(Authentication authentication) {
        UUID ownerUserId = (UUID) authentication.getPrincipal();

        return transactionService.listTransactions(ownerUserId);
    }
}