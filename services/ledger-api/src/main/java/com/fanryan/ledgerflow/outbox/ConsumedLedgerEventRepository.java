package com.fanryan.ledgerflow.outbox;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConsumedLedgerEventRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ConsumedLedgerEventRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfNotExists(ConsumedLedgerEvent event) {
        String sql = """
                INSERT INTO consumed_ledger_events (
                    id,
                    event_type,
                    transaction_id,
                    payload,
                    consumed_at
                )
                VALUES (
                    :id,
                    :eventType,
                    :transactionId,
                    CAST(:payload AS jsonb),
                    :consumedAt
                )
                ON CONFLICT (transaction_id, event_type)
                DO NOTHING
                """;

        int insertedRows = jdbcTemplate.update(sql, Map.of(
                "id", event.id(),
                "eventType", event.eventType(),
                "transactionId", event.transactionId(),
                "payload", event.payload(),
                "consumedAt", event.consumedAt()
        ));

        return insertedRows == 1;
    }

    public int countByTransactionIdAndEventType(
            UUID transactionId,
            String eventType
    ) {
        String sql = """
                SELECT COUNT(*)
                FROM consumed_ledger_events
                WHERE transaction_id = :transactionId
                  AND event_type = :eventType
                """;

        return jdbcTemplate.queryForObject(
                sql,
                Map.of(
                        "transactionId", transactionId,
                        "eventType", eventType
                ),
                Integer.class
        );
    }
}