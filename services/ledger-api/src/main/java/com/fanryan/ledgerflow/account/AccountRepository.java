package com.fanryan.ledgerflow.account;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

public interface AccountRepository extends CrudRepository<Account, UUID> {

    List<Account> findByOwnerUserId(UUID ownerUserId);
}