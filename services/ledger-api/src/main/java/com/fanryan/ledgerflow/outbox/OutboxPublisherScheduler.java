package com.fanryan.ledgerflow.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisherScheduler {

    private final OutboxPublisherService outboxPublisherService;
    private final boolean enabled;

    public OutboxPublisherScheduler(
            OutboxPublisherService outboxPublisherService,
            @Value("${ledgerflow.outbox.publisher.enabled:true}") boolean enabled
    ) {
        this.outboxPublisherService = outboxPublisherService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${ledgerflow.outbox.publisher.fixed-delay-ms:5000}")
    public void publishOutboxEvents() {
        if (!enabled) {
            return;
        }

        outboxPublisherService.publishBatch();
    }
}