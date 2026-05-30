package com.fanryan.ledgerflow.transaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IdempotencyRecord> findByKey(String key) {
        String sql = """
                SELECT key, owner_user_id, request_hash, transaction_id, response_status, response_body, expires_at
                FROM idempotency_keys
                WHERE key = :key
                """;

        return jdbcTemplate.query(
                sql,
                Map.of("key", key),
                (resultSet, rowNum) -> mapRecord(resultSet)
        ).stream().findFirst();
    }

    public void save(IdempotencyRecord record) {
        String sql = """
                INSERT INTO idempotency_keys (
                    key,
                    owner_user_id,
                    request_hash,
                    transaction_id,
                    response_status,
                    response_body,
                    expires_at
                )
                VALUES (
                    :key,
                    :ownerUserId,
                    :requestHash,
                    :transactionId,
                    :responseStatus,
                    CAST(:responseBody AS jsonb),
                    :expiresAt
                )
                """;

        jdbcTemplate.update(sql, Map.of(
                "key", record.key(),
                "ownerUserId", record.ownerUserId(),
                "requestHash", record.requestHash(),
                "transactionId", record.transactionId(),
                "responseStatus", record.responseStatus(),
                "responseBody", record.responseBody(),
                "expiresAt", record.expiresAt()
        ));
    }

    private IdempotencyRecord mapRecord(ResultSet resultSet) throws SQLException {
        return new IdempotencyRecord(
                resultSet.getString("key"),
                resultSet.getObject("owner_user_id", java.util.UUID.class),
                resultSet.getString("request_hash"),
                resultSet.getObject("transaction_id", java.util.UUID.class),
                resultSet.getString("response_status"),
                resultSet.getString("response_body"),
                resultSet.getObject("expires_at", OffsetDateTime.class)
        );
    }
}
