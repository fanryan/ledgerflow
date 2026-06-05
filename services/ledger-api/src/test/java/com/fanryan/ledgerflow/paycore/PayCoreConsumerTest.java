package com.fanryan.ledgerflow.paycore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanryan.ledgerflow.deadletter.DeadLetterEventRepository;
import com.fanryan.ledgerflow.transaction.CreateTransactionRequest;
import com.fanryan.ledgerflow.transaction.TransactionService;
import org.junit.jupiter.api.Test;

class PayCoreConsumerTest {

    @Test
    void consumePaymentCapturedPostsLedgerTransaction() {
        TransactionService transactionService = mock(TransactionService.class);
        DeadLetterEventRepository deadLetterEventRepository = mock(DeadLetterEventRepository.class);
        PayCoreConsumer consumer = new PayCoreConsumer(
                new ObjectMapper(),
                transactionService,
                deadLetterEventRepository
        );

        UUID ownerUserId = UUID.randomUUID();
        UUID merchantAccountId = UUID.randomUUID();

        consumer.consumePaymentCaptured("""
                {
                  "eventId": "evt_capture_001",
                  "paymentId": "pay_001",
                  "ownerUserId": "%s",
                  "merchantAccountId": "%s",
                  "amountMinor": 2500,
                  "currency": "USD",
                  "capturedAt": "2026-06-05T10:00:00Z"
                }
                """.formatted(ownerUserId, merchantAccountId));

        verify(transactionService).submitTransaction(
                eq(ownerUserId),
                eq("evt_capture_001"),
                any(CreateTransactionRequest.class)
        );
    }

    @Test
    void consumePaymentSettledPostsLedgerTransaction() {
        TransactionService transactionService = mock(TransactionService.class);
        DeadLetterEventRepository deadLetterEventRepository = mock(DeadLetterEventRepository.class);
        PayCoreConsumer consumer = new PayCoreConsumer(
                new ObjectMapper(),
                transactionService,
                deadLetterEventRepository
        );

        UUID ownerUserId = UUID.randomUUID();
        UUID merchantAccountId = UUID.randomUUID();

        consumer.consumePaymentSettled("""
                {
                  "eventId": "evt_settle_001",
                  "paymentId": "pay_001",
                  "ownerUserId": "%s",
                  "merchantAccountId": "%s",
                  "amountMinor": 2500,
                  "currency": "USD",
                  "settledAt": "2026-06-05T10:05:00Z"
                }
                """.formatted(ownerUserId, merchantAccountId));

        verify(transactionService).submitTransaction(
                eq(ownerUserId),
                eq("evt_settle_001"),
                any(CreateTransactionRequest.class)
        );
    }

    @Test
    void consumeInvalidPaymentCapturedSavesDeadLetter() {
        TransactionService transactionService = mock(TransactionService.class);
        DeadLetterEventRepository deadLetterEventRepository = mock(DeadLetterEventRepository.class);
        PayCoreConsumer consumer = new PayCoreConsumer(
                new ObjectMapper(),
                transactionService,
                deadLetterEventRepository
        );

        String payload = """
                {
                  "eventId": "",
                  "paymentId": "pay_001",
                  "ownerUserId": "00000000-0000-0000-0000-000000000001",
                  "merchantAccountId": "00000000-0000-0000-0000-000000000002",
                  "amountMinor": 2500,
                  "currency": "USD",
                  "capturedAt": "2026-06-05T10:00:00Z"
                }
                """;

        consumer.consumePaymentCaptured(payload);

        verify(deadLetterEventRepository).save(
                eq("payment.captured"),
                eq(null),
                eq(payload),
                eq("PayCore event id is required")
        );
        verify(transactionService, never()).submitTransaction(
                any(UUID.class),
                any(String.class),
                any(CreateTransactionRequest.class)
        );
    }

    @Test
    void consumeInvalidPaymentSettledSavesDeadLetter() {
        TransactionService transactionService = mock(TransactionService.class);
        DeadLetterEventRepository deadLetterEventRepository = mock(DeadLetterEventRepository.class);
        PayCoreConsumer consumer = new PayCoreConsumer(
                new ObjectMapper(),
                transactionService,
                deadLetterEventRepository
        );

        String payload = """
                {
                  "eventId": "evt_settle_001",
                  "paymentId": "pay_001",
                  "ownerUserId": "00000000-0000-0000-0000-000000000001",
                  "merchantAccountId": "00000000-0000-0000-0000-000000000002",
                  "amountMinor": 2500,
                  "currency": "usd",
                  "settledAt": "2026-06-05T10:05:00Z"
                }
                """;

        consumer.consumePaymentSettled(payload);

        verify(deadLetterEventRepository).save(
                eq("payment.settled"),
                eq(null),
                eq(payload),
                eq("PayCore currency must be a 3-letter uppercase code")
        );
        verify(transactionService, never()).submitTransaction(
                any(UUID.class),
                any(String.class),
                any(CreateTransactionRequest.class)
        );
    }
}
