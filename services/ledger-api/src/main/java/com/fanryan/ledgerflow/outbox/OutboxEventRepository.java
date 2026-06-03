package com.fanryan.ledgerflow.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxEventRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OutboxEventRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(OutboxEvent event) {
        jdbcTemplate.update(
                """
                        INSERT INTO outbox_events (
                            id,
                            aggregate_type,
                            aggregate_id,
                            event_type,
                            payload,
                            status,
                            attempts,
                            next_attempt_at,
                            claimed_by,
                            locked_until,
                            published_at,
                            last_error,
                            created_at,
                            updated_at
                        ) VALUES (
                            :id,
                            :aggregateType,
                            :aggregateId,
                            :eventType,
                            CAST(:payload AS jsonb),
                            :status,
                            :attempts,
                            :nextAttemptAt,
                            :claimedBy,
                            :lockedUntil,
                            :publishedAt,
                            :lastError,
                            :createdAt,
                            :updatedAt
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("id", event.id())
                        .addValue("aggregateType", event.aggregateType())
                        .addValue("aggregateId", event.aggregateId())
                        .addValue("eventType", event.eventType())
                        .addValue("payload", event.payload())
                        .addValue("status", event.status().name())
                        .addValue("attempts", event.attempts())
                        .addValue("nextAttemptAt", event.nextAttemptAt())
                        .addValue("claimedBy", event.claimedBy())
                        .addValue("lockedUntil", event.lockedUntil())
                        .addValue("publishedAt", event.publishedAt())
                        .addValue("lastError", event.lastError())
                        .addValue("createdAt", event.createdAt())
                        .addValue("updatedAt", event.updatedAt())
        );
    }

    public List<OutboxEvent> findByAggregateId(UUID aggregateId) {
        return jdbcTemplate.query(
                """
                        SELECT
                            id,
                            aggregate_type,
                            aggregate_id,
                            event_type,
                            payload::text AS payload,
                            status,
                            attempts,
                            next_attempt_at,
                            claimed_by,
                            locked_until,
                            published_at,
                            last_error,
                            created_at,
                            updated_at
                        FROM outbox_events
                        WHERE aggregate_id = :aggregateId
                        ORDER BY created_at
                        """,
                new MapSqlParameterSource()
                        .addValue("aggregateId", aggregateId),
                (rs, rowNum) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        OutboxEventStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempts"),
                        rs.getObject("next_attempt_at", java.time.OffsetDateTime.class),
                        rs.getString("claimed_by"),
                        rs.getObject("locked_until", java.time.OffsetDateTime.class),
                        rs.getObject("published_at", java.time.OffsetDateTime.class),
                        rs.getString("last_error"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class)
                )
        );
    }

    public List<OutboxEvent> claimPublishableEvents(
            String claimedBy,
            OffsetDateTime lockedUntil,
            int limit
    ) {
        return jdbcTemplate.query(
                """
                        UPDATE outbox_events
                        SET
                            status = 'PROCESSING',
                            claimed_by = :claimedBy,
                            locked_until = :lockedUntil,
                            updated_at = now()
                        WHERE id IN (
                            SELECT id
                            FROM outbox_events
                            WHERE
                                (
                                    status IN ('PENDING', 'FAILED')
                                    AND next_attempt_at <= now()
                                )
                                OR (
                                    status = 'PROCESSING'
                                    AND locked_until < now()
                                )
                            ORDER BY created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        RETURNING
                            id,
                            aggregate_type,
                            aggregate_id,
                            event_type,
                            payload::text AS payload,
                            status,
                            attempts,
                            next_attempt_at,
                            claimed_by,
                            locked_until,
                            published_at,
                            last_error,
                            created_at,
                            updated_at
                        """,
                new MapSqlParameterSource()
                        .addValue("claimedBy", claimedBy)
                        .addValue("lockedUntil", lockedUntil)
                        .addValue("limit", limit),
                (rs, rowNum) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        OutboxEventStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempts"),
                        rs.getObject("next_attempt_at", OffsetDateTime.class),
                        rs.getString("claimed_by"),
                        rs.getObject("locked_until", OffsetDateTime.class),
                        rs.getObject("published_at", OffsetDateTime.class),
                        rs.getString("last_error"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                )
        );
    }

    public void markPublished(UUID eventId) {
        jdbcTemplate.update(
                """
                        UPDATE outbox_events
                        SET
                            status = 'PUBLISHED',
                            published_at = now(),
                            claimed_by = NULL,
                            locked_until = NULL,
                            last_error = NULL,
                            updated_at = now()
                        WHERE id = :eventId
                        """,
                new MapSqlParameterSource()
                        .addValue("eventId", eventId)
        );
    }

    public void markFailed(
            UUID eventId,
            String lastError,
            OffsetDateTime nextAttemptAt
    ) {
        jdbcTemplate.update(
                """
                        UPDATE outbox_events
                        SET
                            status = 'FAILED',
                            attempts = attempts + 1,
                            next_attempt_at = :nextAttemptAt,
                            claimed_by = NULL,
                            locked_until = NULL,
                            last_error = :lastError,
                            updated_at = now()
                        WHERE id = :eventId
                        """,
                new MapSqlParameterSource()
                        .addValue("eventId", eventId)
                        .addValue("lastError", lastError)
                        .addValue("nextAttemptAt", nextAttemptAt)
        );
    }
}
