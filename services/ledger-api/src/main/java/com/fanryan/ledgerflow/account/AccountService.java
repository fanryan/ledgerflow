package com.fanryan.ledgerflow.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fanryan.ledgerflow.ledger.LedgerEntryRepository;
import com.fanryan.ledgerflow.ledger.LedgerEntryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountService(
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository
    ) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public AccountResponse createAccount(UUID ownerUserId, CreateAccountRequest request) {
        String currency = normalizeCurrency(request.currency());
        OffsetDateTime now = OffsetDateTime.now();

        Account account = new Account(
                UUID.randomUUID(),
                ownerUserId,
                currency,
                AccountStatus.ACTIVE,
                0,
                0,
                now,
                now
        );

        Account savedAccount = accountRepository.save(account);

        return AccountResponse.from(savedAccount);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(UUID ownerUserId) {
        return accountRepository.findByOwnerUserId(ownerUserId)
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> listLedgerEntries(
            UUID ownerUserId,
            UUID accountId
    ) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(AccountNotFoundException::new);

        if (!account.ownerUserId().equals(ownerUserId)) {
            throw new AccountOwnershipException();
        }

        return ledgerEntryRepository
                .findByAccountIdOrderByCreatedAtDesc(accountId)
                .stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new InvalidAccountRequestException("Currency is required");
        }

        String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);

        if (!normalizedCurrency.matches("[A-Z]{3}")) {
            throw new InvalidAccountRequestException("Currency must be a 3-letter code");
        }

        return normalizedCurrency;
    }
}