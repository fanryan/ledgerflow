package com.fanryan.ledgerflow.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublisherService {

    private static final String LEDGER_EVENTS_TOPIC = "ledger.events";
    private static final int DEFAULT_BATCH_SIZE = 25;
    private static final int LOCK_TTL_MINUTES = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String publisherId;

    public OutboxPublisherService(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.publisherId = "ledger-api-" + UUID.randomUUID();
    }

    @Transactional
    public int publishBatch() {
        OffsetDateTime lockedUntil = OffsetDateTime.now().plusMinutes(LOCK_TTL_MINUTES);

        List<OutboxEvent> events = outboxEventRepository.claimPublishableEvents(
                publisherId,
                lockedUntil,
                DEFAULT_BATCH_SIZE
        );

        for (OutboxEvent event : events) {
            publishEvent(event);
        }

        return events.size();
    }

    private void publishEvent(OutboxEvent event) {
        try {
            kafkaTemplate.send(
                    LEDGER_EVENTS_TOPIC,
                    event.aggregateId().toString(),
                    event.payload()
            ).get();

            outboxEventRepository.markPublished(event.id());
        } catch (Exception exception) {
            outboxEventRepository.markFailed(
                    event.id(),
                    exception.getMessage(),
                    OffsetDateTime.now().plusMinutes(nextRetryDelayMinutes(event.attempts()))
            );
        }
    }

    private long nextRetryDelayMinutes(int attempts) {
        return Math.min(30, (long) Math.pow(2, attempts));
    }
}