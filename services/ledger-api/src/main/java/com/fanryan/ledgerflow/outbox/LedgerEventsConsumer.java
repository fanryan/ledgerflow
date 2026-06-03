package com.fanryan.ledgerflow.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LedgerEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventsConsumer.class);

    @KafkaListener(
            topics = "ledger.events",
            groupId = "${ledgerflow.kafka.consumer-group-id:ledgerflow-ledger-api}"
    )
    public void consume(String payload) {
        log.info("Consumed ledger event: {}", payload);
    }
}