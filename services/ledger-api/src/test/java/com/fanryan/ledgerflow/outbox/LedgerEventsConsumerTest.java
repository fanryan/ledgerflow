package com.fanryan.ledgerflow.outbox;

import org.junit.jupiter.api.Test;

class LedgerEventsConsumerTest {

    @Test
    void consumeAcceptsLedgerEventPayload() {
        LedgerEventsConsumer consumer = new LedgerEventsConsumer();

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
    }
}