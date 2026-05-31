package com.fanryan.ledgerflow.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    public void savePendingEvent(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload
    ) {
        OffsetDateTime now = OffsetDateTime.now();

        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                payload,
                OutboxEventStatus.PENDING,
                0,
                now,
                null,
                null,
                null,
                null,
                now,
                now
        );

        outboxEventRepository.save(event);
    }
}
