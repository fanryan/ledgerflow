package com.fanryan.ledgerflow.deadletter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fanryan.ledgerflow.support.IntegrationTestSupport;

class DeadLetterEventRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private DeadLetterEventRepository deadLetterEventRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void savePersistsPendingDeadLetterEvent() {
        String payload = """
                {
                  "eventId": "",
                  "paymentId": "pay_001",
                  "amountMinor": 2500
                }
                """;

        deadLetterEventRepository.save(
                "payment.captured",
                null,
                payload,
                "PayCore event id is required"
        );

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT
                    source_topic,
                    event_key,
                    payload::text AS payload,
                    error_message,
                    status,
                    attempts,
                    replayed_at
                FROM dead_letter_events
                WHERE source_topic = :sourceTopic
                ORDER BY created_at DESC
                LIMIT 1
                """,
                Map.of("sourceTopic", "payment.captured")
        );

        assertThat(row.get("source_topic")).isEqualTo("payment.captured");
        assertThat(row.get("event_key")).isNull();
        assertThat(row.get("payload").toString()).contains("\"paymentId\": \"pay_001\"");
        assertThat(row.get("error_message")).isEqualTo("PayCore event id is required");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0L);
        assertThat(row.get("replayed_at")).isNull();
    }

    @Test
    void findPendingAndMarkReplayedUpdatesDeadLetterEvent() {
        String payload = """
                {
                  "eventId": "evt_bad_001",
                  "paymentId": "pay_001"
                }
                """;

        deadLetterEventRepository.save(
                "payment.settled",
                "evt_bad_001",
                payload,
                "Invalid test event"
        );

        DeadLetterEvent pending = deadLetterEventRepository.findPending(10)
                .stream()
                .filter(event -> "evt_bad_001".equals(event.eventKey()))
                .findFirst()
                .orElseThrow();

        assertThat(pending.sourceTopic()).isEqualTo("payment.settled");
        assertThat(pending.status()).isEqualTo(DeadLetterEventStatus.PENDING);
        assertThat(pending.attempts()).isZero();
        assertThat(pending.replayedAt()).isNull();

        deadLetterEventRepository.markReplayed(pending.id());

        DeadLetterEvent replayed = deadLetterEventRepository.findById(pending.id())
                .orElseThrow();

        assertThat(replayed.status()).isEqualTo(DeadLetterEventStatus.REPLAYED);
        assertThat(replayed.attempts()).isEqualTo(1L);
        assertThat(replayed.replayedAt()).isNotNull();
    }
}
