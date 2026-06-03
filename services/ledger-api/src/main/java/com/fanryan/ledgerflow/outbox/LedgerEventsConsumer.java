package com.fanryan.ledgerflow.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LedgerEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventsConsumer.class);

    private final ObjectMapper objectMapper;
    private final ConsumedLedgerEventRepository consumedLedgerEventRepository;

    public LedgerEventsConsumer(
            ObjectMapper objectMapper,
            ConsumedLedgerEventRepository consumedLedgerEventRepository
    ) {
        this.objectMapper = objectMapper;
        this.consumedLedgerEventRepository = consumedLedgerEventRepository;
    }

    @KafkaListener(
            topics = "ledger.events",
            groupId = "${ledgerflow.kafka.consumer-group-id:ledgerflow-ledger-api}"
    )
    public void consume(String payload) {
        JsonNode event = parsePayload(payload);

        UUID transactionId = UUID.fromString(event.get("transactionId").asText());
        String eventType = "TRANSACTION_POSTED";

        boolean inserted = consumedLedgerEventRepository.insertIfNotExists(
                new ConsumedLedgerEvent(
                        UUID.randomUUID(),
                        eventType,
                        transactionId,
                        payload,
                        OffsetDateTime.now()
                )
        );

        if (inserted) {
            log.info("Consumed ledger event type={} transactionId={}", eventType, transactionId);
            return;
        }

        log.info("Skipped duplicate ledger event type={} transactionId={}", eventType, transactionId);
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid ledger event payload", exception);
        }
    }
}