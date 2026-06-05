package com.fanryan.ledgerflow.paycore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fanryan.ledgerflow.account.Account;
import com.fanryan.ledgerflow.account.AccountRepository;
import com.fanryan.ledgerflow.account.AccountStatus;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;
import com.fanryan.ledgerflow.transaction.Transaction;
import com.fanryan.ledgerflow.transaction.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class PayCoreKafkaIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void paymentCapturedEventCreatesLedgerTransaction() {
        UUID ownerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Account account = createActiveUsdAccount(ownerUserId);

        String eventId = "it-paycore-captured-" + UUID.randomUUID();
        String payload = """
                {
                  "eventId": "%s",
                  "paymentId": "pay_it_001",
                  "ownerUserId": "%s",
                  "merchantAccountId": "%s",
                  "amountMinor": 2500,
                  "currency": "USD",
                  "capturedAt": "2026-06-05T10:00:00Z"
                }
                """.formatted(eventId, ownerUserId, account.id());

        kafkaTemplate.send("payment.captured", eventId, payload).join();

        Transaction transaction = waitForTransaction(eventId);

        assertThat(transaction.ownerUserId()).isEqualTo(ownerUserId);
        assertThat(transaction.accountId()).isEqualTo(account.id());
        assertThat(transaction.idempotencyKey()).isEqualTo(eventId);
        assertThat(transaction.amountMinor()).isEqualTo(2500);
        assertThat(transaction.currency()).isEqualTo("USD");
    }

    @Test
    void paymentSettledEventCreatesLedgerTransaction() {
        UUID ownerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Account account = createActiveUsdAccount(ownerUserId);

        String eventId = "it-paycore-settled-" + UUID.randomUUID();
        String payload = """
                {
                  "eventId": "%s",
                  "paymentId": "pay_it_002",
                  "ownerUserId": "%s",
                  "merchantAccountId": "%s",
                  "amountMinor": 3700,
                  "currency": "USD",
                  "settledAt": "2026-06-05T10:05:00Z"
                }
                """.formatted(eventId, ownerUserId, account.id());

        kafkaTemplate.send("payment.settled", eventId, payload).join();

        Transaction transaction = waitForTransaction(eventId);

        assertThat(transaction.ownerUserId()).isEqualTo(ownerUserId);
        assertThat(transaction.accountId()).isEqualTo(account.id());
        assertThat(transaction.idempotencyKey()).isEqualTo(eventId);
        assertThat(transaction.amountMinor()).isEqualTo(3700);
        assertThat(transaction.currency()).isEqualTo("USD");
    }

    private Account createActiveUsdAccount(UUID ownerUserId) {
        OffsetDateTime now = OffsetDateTime.now();

        return accountRepository.save(new Account(
                UUID.randomUUID(),
                ownerUserId,
                "USD",
                AccountStatus.ACTIVE,
                0,
                0,
                now,
                now
        ));
    }

    private Transaction waitForTransaction(String idempotencyKey) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

        while (System.nanoTime() < deadline) {
            var transaction = transactionRepository.findByIdempotencyKey(idempotencyKey);

            if (transaction.isPresent()) {
                return transaction.get();
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for PayCore transaction", exception);
            }
        }

        throw new AssertionError("PayCore transaction was not created for idempotency key: " + idempotencyKey);
    }
}
