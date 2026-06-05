package com.fanryan.ledgerflow.deadletter;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeadLetterEventRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DeadLetterEventRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
            String sourceTopic,
            String eventKey,
            String payload,
            String errorMessage
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO dead_letter_events (
                    id,
                    source_topic,
                    event_key,
                    payload,
                    error_message,
                    status,
                    attempts,
                    created_at,
                    replayed_at
                )
                VALUES (
                    :id,
                    :sourceTopic,
                    :eventKey,
                    CAST(:payload AS jsonb),
                    :errorMessage,
                    :status,
                    :attempts,
                    :createdAt,
                    NULL
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("sourceTopic", sourceTopic)
                        .addValue("eventKey", eventKey)
                        .addValue("payload", payload)
                        .addValue("errorMessage", errorMessage)
                        .addValue("status", DeadLetterEventStatus.PENDING.name())
                        .addValue("attempts", 0)
                        .addValue("createdAt", OffsetDateTime.now())
        );
    }

    public List<DeadLetterEvent> findPending(int limit) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    source_topic,
                    event_key,
                    payload::text AS payload,
                    error_message,
                    status,
                    attempts,
                    created_at,
                    replayed_at
                FROM dead_letter_events
                WHERE status = :status
                ORDER BY created_at
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("status", DeadLetterEventStatus.PENDING.name())
                        .addValue("limit", limit),
                (rs, rowNum) -> mapRow(rs)
        );
    }

    public Optional<DeadLetterEvent> findById(UUID id) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    source_topic,
                    event_key,
                    payload::text AS payload,
                    error_message,
                    status,
                    attempts,
                    created_at,
                    replayed_at
                FROM dead_letter_events
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id),
                (rs, rowNum) -> mapRow(rs)
        ).stream().findFirst();
    }

    public void markReplayed(UUID id) {
        jdbcTemplate.update(
                """
                UPDATE dead_letter_events
                SET
                    status = :status,
                    attempts = attempts + 1,
                    replayed_at = :replayedAt
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", DeadLetterEventStatus.REPLAYED.name())
                        .addValue("replayedAt", OffsetDateTime.now())
        );
    }

    private DeadLetterEvent mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DeadLetterEvent(
                rs.getObject("id", UUID.class),
                rs.getString("source_topic"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getString("error_message"),
                DeadLetterEventStatus.valueOf(rs.getString("status")),
                rs.getLong("attempts"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("replayed_at", OffsetDateTime.class)
        );
    }
}