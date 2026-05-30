package com.fanryan.ledgerflow.transaction;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

public interface TransactionRepository extends CrudRepository<Transaction, UUID> {

    List<Transaction> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    List<Transaction> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
