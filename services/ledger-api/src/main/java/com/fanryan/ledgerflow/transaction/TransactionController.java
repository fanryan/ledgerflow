package com.fanryan.ledgerflow.transaction;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
        UUID ownerUserId = UUID.fromString(authentication.getPrincipal().toString());

        return transactionService.submitTransaction(ownerUserId, idempotencyKey, request);
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> listTransactions(Authentication authentication) {
        UUID ownerUserId = UUID.fromString(authentication.getPrincipal().toString());

        return transactionService.listTransactions(ownerUserId);
    }

    @PostMapping("/transactions/{transactionId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse reverseTransaction(
            Authentication authentication,
            @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReverseTransactionRequest request
    ) {
        UUID ownerUserId = UUID.fromString(authentication.getPrincipal().toString());

        return transactionService.reverseTransaction(
                ownerUserId,
                transactionId,
                idempotencyKey,
                request
        );
    }
}