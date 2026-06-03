package com.fanryan.ledgerflow.reconciliation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/reconciliation/ledger-balance-check")
    @ResponseStatus(HttpStatus.CREATED)
    public ReconciliationReport runLedgerBalanceCheck() {
        return reconciliationService.runLedgerBalanceCheck();
    }
}