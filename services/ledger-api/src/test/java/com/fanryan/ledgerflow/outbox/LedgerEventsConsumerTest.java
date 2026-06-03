package com.fanryan.ledgerflow.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LedgerEventsConsumerTest {

    @Test
    void consumeStoresLedgerEventPayload() {
        ConsumedLedgerEventRepository repository = mock(ConsumedLedgerEventRepository.class);
        LedgerEventsConsumer consumer = new LedgerEventsConsumer(
                new ObjectMapper(),
                repository
        );

        when(repository.insertIfNotExists(any(ConsumedLedgerEvent.class)))
                .thenReturn(true);

        consumer.consume("""
                {
                  "transactionId": "b70ecfd2-55fc-47d1-8676-fe54c530c025",
                  "accountId": "55899b23-bc0a-42fa-a117-83b8561d9f1b",
                  "ownerUserId": "00000000-0000-0000-0000-000000000001",
                  "idempotencyKey": "tx-outbox-manual-003",
                  "type": "DEPOSIT",
                  "amountMinor": 1000,
                  "currency": "USD",
                  "status": "POSTED",
                  "reversalOfTransactionId": null
                }
                """);

        verify(repository).insertIfNotExists(any(ConsumedLedgerEvent.class));
    }
}