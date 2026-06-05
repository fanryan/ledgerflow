package com.fanryan.ledgerflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;

class OutboxEventRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOutboxEvents() {
        jdbcTemplate.update("DELETE FROM outbox_events", java.util.Map.of());
    }

    @Test
    void claimPublishableEventsMarksPendingEventsAsProcessing() {
        OutboxEvent event = savePendingEvent();

        List<OutboxEvent> claimedEvents = outboxEventRepository.claimPublishableEvents(
                "test-publisher",
                OffsetDateTime.now().plusMinutes(5),
                10
        );

        assertThat(claimedEvents)
                .extracting(OutboxEvent::id)
                .contains(event.id());

        OutboxEvent claimedEvent = claimedEvents.stream()
                .filter(candidate -> candidate.id().equals(event.id()))
                .findFirst()
                .orElseThrow();

        assertThat(claimedEvent.status()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(claimedEvent.claimedBy()).isEqualTo("test-publisher");
        assertThat(claimedEvent.lockedUntil()).isNotNull();
    }

    @Test
    void markPublishedMarksEventAsPublishedAndClearsClaimFields() {
        OutboxEvent event = savePendingEvent();

        OutboxEvent claimedEvent = outboxEventRepository.claimPublishableEvents(
                "test-publisher",
                OffsetDateTime.now().plusMinutes(5),
                10
        ).stream()
                .filter(candidate -> candidate.id().equals(event.id()))
                .findFirst()
                .orElseThrow();

        outboxEventRepository.markPublished(claimedEvent.id());

        OutboxEvent publishedEvent = outboxEventRepository.findByAggregateId(event.aggregateId())
                .stream()
                .filter(candidate -> candidate.id().equals(event.id()))
                .findFirst()
                .orElseThrow();

        assertThat(publishedEvent.status()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(publishedEvent.claimedBy()).isNull();
        assertThat(publishedEvent.lockedUntil()).isNull();
        assertThat(publishedEvent.publishedAt()).isNotNull();
        assertThat(publishedEvent.lastError()).isNull();
    }

    @Test
    void markFailedMarksEventAsFailedAndSchedulesRetry() {
        OutboxEvent event = savePendingEvent();

        OutboxEvent claimedEvent = outboxEventRepository.claimPublishableEvents(
                "test-publisher",
                OffsetDateTime.now().plusMinutes(5),
                10
        ).stream()
                .filter(candidate -> candidate.id().equals(event.id()))
                .findFirst()
                .orElseThrow();

        OffsetDateTime nextAttemptAt = OffsetDateTime.now().plusMinutes(2);

        outboxEventRepository.markFailed(
                claimedEvent.id(),
                "Kafka send failed",
                nextAttemptAt
        );

        OutboxEvent failedEvent = outboxEventRepository.findByAggregateId(event.aggregateId())
                .stream()
                .filter(candidate -> candidate.id().equals(event.id()))
                .findFirst()
                .orElseThrow();

        assertThat(failedEvent.status()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(failedEvent.attempts()).isEqualTo(1);
        assertThat(failedEvent.claimedBy()).isNull();
        assertThat(failedEvent.lockedUntil()).isNull();
        assertThat(failedEvent.lastError()).isEqualTo("Kafka send failed");
        assertThat(failedEvent.nextAttemptAt()).isAfter(OffsetDateTime.now());
    }

    private OutboxEvent savePendingEvent() {
        OffsetDateTime now = OffsetDateTime.now();

        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                "TRANSACTION",
                UUID.randomUUID(),
                "TRANSACTION_POSTED",
                """
                        {"transactionId":"test-transaction"}
                        """,
                OutboxEventStatus.PENDING,
                0,
                now.minusSeconds(1),
                null,
                null,
                null,
                null,
                now,
                now
        );

        outboxEventRepository.save(event);

        return event;
    }
}
