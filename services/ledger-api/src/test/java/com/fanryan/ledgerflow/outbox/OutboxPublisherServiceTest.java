package com.fanryan.ledgerflow.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherServiceTest {

    @Test
    void publishBatchPublishesClaimedEventsAndMarksThemPublished() throws Exception {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

        OutboxEvent event = pendingEvent();

        when(outboxEventRepository.claimPublishableEvents(
                any(String.class),
                any(OffsetDateTime.class),
                eq(25)
        )).thenReturn(List.of(event));

        when(kafkaTemplate.send(
                eq("ledger.events"),
                eq(event.aggregateId().toString()),
                eq(event.payload())
        )).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        OutboxPublisherService service = new OutboxPublisherService(
                outboxEventRepository,
                kafkaTemplate
        );

        int publishedCount = service.publishBatch();

        verify(kafkaTemplate).send(
                "ledger.events",
                event.aggregateId().toString(),
                event.payload()
        );

        verify(outboxEventRepository).markPublished(event.id());

        assertThat(publishedCount).isEqualTo(1);
    }

    @Test
    void publishBatchMarksEventFailedWhenKafkaSendFails() {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

        OutboxEvent event = pendingEvent();

        when(outboxEventRepository.claimPublishableEvents(
                any(String.class),
                any(OffsetDateTime.class),
                eq(25)
        )).thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka unavailable"));

        when(kafkaTemplate.send(
                eq("ledger.events"),
                eq(event.aggregateId().toString()),
                eq(event.payload())
        )).thenReturn(failedFuture);

        OutboxPublisherService service = new OutboxPublisherService(
                outboxEventRepository,
                kafkaTemplate
        );

        int publishedCount = service.publishBatch();

        verify(outboxEventRepository).markFailed(
                eq(event.id()),
                any(String.class),
                any(OffsetDateTime.class)
        );

        assertThat(publishedCount).isEqualTo(1);
    }

    private OutboxEvent pendingEvent() {
        OffsetDateTime now = OffsetDateTime.now();

        return new OutboxEvent(
                UUID.randomUUID(),
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSACTION_POSTED",
                "{\"transactionId\":\"test-transaction\"}",
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
    }
}