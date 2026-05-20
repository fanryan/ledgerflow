package com.fanryan.ledgerflow.transaction;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import com.fanryan.ledgerflow.account.Account;
import com.fanryan.ledgerflow.account.AccountRepository;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    public TransactionResponse submitTransaction(
            UUID ownerUserId,
            String idempotencyKey,
            CreateTransactionRequest request
    ) {
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        return transactionRepository
                .findByOwnerUserIdAndIdempotencyKey(ownerUserId, normalizedIdempotencyKey)
                .map(TransactionResponse::from)
                .orElseGet(() -> createNewTransaction(
                        ownerUserId,
                        normalizedIdempotencyKey,
                        request
                ));
    }

    private TransactionResponse createNewTransaction(
            UUID ownerUserId,
            String idempotencyKey,
            CreateTransactionRequest request
    ) {
        validateRequest(request);

        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(AccountNotFoundException::new);

        if (!account.ownerUserId().equals(ownerUserId)) {
            throw new AccountOwnershipException();
        }

        String currency = normalizeCurrency(request.currency());

        if (!account.currency().equals(currency)) {
            throw new InvalidTransactionRequestException("Currency must match account currency");
        }

        OffsetDateTime now = OffsetDateTime.now();

        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                account.id(),
                ownerUserId,
                idempotencyKey,
                request.type(),
                request.amountMinor(),
                currency,
                TransactionStatus.PENDING,
                request.description(),
                0,
                now,
                now
        );

        Transaction savedTransaction = transactionRepository.save(transaction);

        return TransactionResponse.from(savedTransaction);
    }

    private void validateRequest(CreateTransactionRequest request) {
        if (request.accountId() == null) {
            throw new InvalidTransactionRequestException("Account id is required");
        }

        if (request.type() == null) {
            throw new InvalidTransactionRequestException("Transaction type is required");
        }

        if (request.amountMinor() <= 0) {
            throw new InvalidTransactionRequestException("Amount must be greater than zero");
        }
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new InvalidTransactionRequestException("Currency is required");
        }

        String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);

        if (!normalizedCurrency.matches("[A-Z]{3}")) {
            throw new InvalidTransactionRequestException("Currency must be a 3-letter code");
        }

        return normalizedCurrency;
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidTransactionRequestException("Idempotency-Key header is required");
        }

        String normalizedIdempotencyKey = idempotencyKey.trim();

        if (normalizedIdempotencyKey.length() > 100) {
            throw new InvalidTransactionRequestException("Idempotency-Key must be 100 characters or fewer");
        }

        return normalizedIdempotencyKey;
    }
}