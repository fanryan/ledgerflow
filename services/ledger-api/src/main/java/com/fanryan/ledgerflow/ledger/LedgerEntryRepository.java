package com.fanryan.ledgerflow.ledger;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

public interface LedgerEntryRepository extends CrudRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}