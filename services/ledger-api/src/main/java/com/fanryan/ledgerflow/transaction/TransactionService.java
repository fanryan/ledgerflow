package com.fanryan.ledgerflow.transaction;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import com.fanryan.ledgerflow.account.Account;
import com.fanryan.ledgerflow.account.AccountRepository;
import com.fanryan.ledgerflow.ledger.LedgerEntry;
import com.fanryan.ledgerflow.ledger.LedgerEntryDirection;
import com.fanryan.ledgerflow.ledger.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public TransactionResponse submitTransaction(
            UUID ownerUserId,
            String idempotencyKey,
            CreateTransactionRequest request
    ) {
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        return transactionRepository
                .findByOwnerUserIdAndIdempotencyKey(ownerUserId, normalizedIdempotencyKey)
                .map(TransactionResponse::from)
                .orElseGet(() -> createAndPostTransaction(
                        ownerUserId,
                        normalizedIdempotencyKey,
                        request
                ));
    }

    private TransactionResponse createAndPostTransaction(
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

        Transaction pendingTransaction = new Transaction(
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

        Transaction savedPendingTransaction = transactionRepository.save(pendingTransaction);

        long newBalanceMinor = calculateNewBalance(
                account.balanceMinor(),
                request.type(),
                request.amountMinor()
        );

        LedgerEntryDirection direction = ledgerEntryDirectionFor(request.type());

        LedgerEntry ledgerEntry = new LedgerEntry(
                UUID.randomUUID(),
                savedPendingTransaction.id(),
                account.id(),
                direction,
                request.amountMinor(),
                currency,
                0,
                now
        );

        ledgerEntryRepository.save(ledgerEntry);

        Account updatedAccount = account.withBalance(newBalanceMinor, now);
        accountRepository.save(updatedAccount);

        Transaction postedTransaction = new Transaction(
                savedPendingTransaction.id(),
                savedPendingTransaction.accountId(),
                savedPendingTransaction.ownerUserId(),
                savedPendingTransaction.idempotencyKey(),
                savedPendingTransaction.type(),
                savedPendingTransaction.amountMinor(),
                savedPendingTransaction.currency(),
                TransactionStatus.POSTED,
                savedPendingTransaction.description(),
                savedPendingTransaction.version(),
                savedPendingTransaction.createdAt(),
                now
        );

        Transaction savedPostedTransaction = transactionRepository.save(postedTransaction);

        return TransactionResponse.from(savedPostedTransaction);
    }

    private long calculateNewBalance(
            long currentBalanceMinor,
            TransactionType type,
            long amountMinor
    ) {
        return switch (type) {
            case DEPOSIT -> currentBalanceMinor + amountMinor;
            case WITHDRAWAL -> {
                if (currentBalanceMinor < amountMinor) {
                    throw new InsufficientFundsException();
                }

                yield currentBalanceMinor - amountMinor;
            }
        };
    }

    private LedgerEntryDirection ledgerEntryDirectionFor(TransactionType type) {
        return switch (type) {
            case DEPOSIT -> LedgerEntryDirection.CREDIT;
            case WITHDRAWAL -> LedgerEntryDirection.DEBIT;
        };
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