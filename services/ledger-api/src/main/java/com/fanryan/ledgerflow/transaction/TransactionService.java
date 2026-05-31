package com.fanryan.ledgerflow.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanryan.ledgerflow.account.Account;
import com.fanryan.ledgerflow.account.AccountNotFoundException;
import com.fanryan.ledgerflow.account.AccountOwnershipException;
import com.fanryan.ledgerflow.account.AccountRepository;
import com.fanryan.ledgerflow.ledger.LedgerEntry;
import com.fanryan.ledgerflow.ledger.LedgerEntryDirection;
import com.fanryan.ledgerflow.ledger.LedgerEntryRepository;
import com.fanryan.ledgerflow.ledger.SystemAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository,
            IdempotencyRepository idempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(noRollbackFor = InsufficientFundsException.class)
    public TransactionResponse submitTransaction(
            UUID ownerUserId,
            String idempotencyKey,
            CreateTransactionRequest request
    ) {
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        validateRequest(request);
        String requestHash = hashRequest(request);

        return idempotencyRepository.findByKey(normalizedIdempotencyKey)
                .map(record -> replayOrRejectIdempotentRequest(record, ownerUserId, requestHash))
                .orElseGet(() -> createAndPostTransaction(
                        ownerUserId,
                        normalizedIdempotencyKey,
                        requestHash,
                        request
                ));
    }

    private TransactionResponse replayOrRejectIdempotentRequest(
            IdempotencyRecord record,
            UUID ownerUserId,
            String requestHash
    ) {
        if (record.expiresAt().isBefore(OffsetDateTime.now())) {
            throw new ExpiredIdempotencyKeyException();
        }

        if (!record.ownerUserId().equals(ownerUserId)) {
            throw new IdempotencyConflictException();
        }

        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }

        Transaction transaction = transactionRepository.findById(record.transactionId())
                .orElseThrow(() -> new IllegalStateException("Idempotency record points to missing transaction"));

        if (transaction.status() == TransactionStatus.FAILED) {
            throw new InsufficientFundsException();
        }

        return TransactionResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> listTransactions(UUID ownerUserId) {
        return transactionRepository
                .findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId)
                .stream()
                .map(TransactionResponse::from)
                .toList();
    }

    private TransactionResponse createAndPostTransaction(
            UUID ownerUserId,
            String idempotencyKey,
            String requestHash,
            CreateTransactionRequest request
    ) {
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(AccountNotFoundException::new);

        if (!account.ownerUserId().equals(ownerUserId)) {
            throw new AccountOwnershipException();
        }

        String currency = normalizeCurrency(request.currency());

        if (!account.currency().equals(currency)) {
            throw new CurrencyMismatchException();
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

        Long newBalanceMinor = calculateNewBalanceOrNull(
                account.balanceMinor(),
                request.type(),
                request.amountMinor()
        );

        if (newBalanceMinor == null) {
            Transaction failedTransaction = new Transaction(
                    savedPendingTransaction.id(),
                    savedPendingTransaction.accountId(),
                    savedPendingTransaction.ownerUserId(),
                    savedPendingTransaction.idempotencyKey(),
                    savedPendingTransaction.type(),
                    savedPendingTransaction.amountMinor(),
                    savedPendingTransaction.currency(),
                    TransactionStatus.FAILED,
                    savedPendingTransaction.description(),
                    savedPendingTransaction.version(),
                    savedPendingTransaction.createdAt(),
                    now
            );

            transactionRepository.save(failedTransaction);
            saveIdempotencyRecord(
                    idempotencyKey,
                    ownerUserId,
                    requestHash,
                    failedTransaction.id(),
                    failedTransaction.status().name(),
                    """
                            {"errorCode":"INSUFFICIENT_FUNDS","message":"Insufficient funds"}
                            """
            );

            throw new InsufficientFundsException();
        }

        LedgerEntry userLedgerEntry = new LedgerEntry(
                UUID.randomUUID(),
                savedPendingTransaction.id(),
                account.id(),
                userLedgerEntryDirectionFor(request.type()),
                request.amountMinor(),
                currency,
                0,
                now
        );

        LedgerEntry systemLedgerEntry = new LedgerEntry(
                UUID.randomUUID(),
                savedPendingTransaction.id(),
                SystemAccounts.USD_SETTLEMENT_ACCOUNT_ID,
                systemLedgerEntryDirectionFor(request.type()),
                request.amountMinor(),
                currency,
                0,
                now
        );

        ledgerEntryRepository.save(userLedgerEntry);
        ledgerEntryRepository.save(systemLedgerEntry);

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
        TransactionResponse response = TransactionResponse.from(savedPostedTransaction);

        saveIdempotencyRecord(
                idempotencyKey,
                ownerUserId,
                requestHash,
                savedPostedTransaction.id(),
                savedPostedTransaction.status().name(),
                toJson(response)
        );

        return response;
    }

    private Long calculateNewBalanceOrNull(
            long currentBalanceMinor,
            TransactionType type,
            long amountMinor
    ) {
        return switch (type) {
            case DEPOSIT -> currentBalanceMinor + amountMinor;
            case WITHDRAWAL -> {
                if (currentBalanceMinor < amountMinor) {
                    yield null;
                }

                yield currentBalanceMinor - amountMinor;
            }
        };
    }

    private LedgerEntryDirection userLedgerEntryDirectionFor(TransactionType type) {
        return switch (type) {
            case DEPOSIT -> LedgerEntryDirection.CREDIT;
            case WITHDRAWAL -> LedgerEntryDirection.DEBIT;
        };
    }

    private LedgerEntryDirection systemLedgerEntryDirectionFor(TransactionType type) {
        return switch (type) {
            case DEPOSIT -> LedgerEntryDirection.DEBIT;
            case WITHDRAWAL -> LedgerEntryDirection.CREDIT;
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

    private String hashRequest(CreateTransactionRequest request) {
        String normalizedCurrency = request.currency() == null
                ? ""
                : request.currency().trim().toUpperCase(Locale.ROOT);

        String canonicalRequest = String.join(
                "|",
                String.valueOf(request.accountId()),
                String.valueOf(request.type()),
                String.valueOf(request.amountMinor()),
                normalizedCurrency,
                request.description() == null ? "" : request.description()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));

            StringBuilder hash = new StringBuilder();

            for (byte hashByte : hashBytes) {
                hash.append("%02x".formatted(hashByte));
            }

            return hash.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void saveIdempotencyRecord(
            String idempotencyKey,
            UUID ownerUserId,
            String requestHash,
            UUID transactionId,
            String responseStatus,
            String responseBody
    ) {
        idempotencyRepository.save(new IdempotencyRecord(
                idempotencyKey,
                ownerUserId,
                requestHash,
                transactionId,
                responseStatus,
                responseBody,
                OffsetDateTime.now().plusHours(24)
        ));
    }

    private String toJson(TransactionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize transaction response", exception);
        }
    }
}
