package com.fanryan.ledgerflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;

class ConsumedLedgerEventRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private ConsumedLedgerEventRepository repository;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearConsumedEvents() {
        jdbcTemplate.update("DELETE FROM consumed_ledger_events", Map.of());
    }

    @Test
    void insertIfNotExistsStoresNewConsumedEvent() {
        UUID transactionId = UUID.randomUUID();

        boolean inserted = repository.insertIfNotExists(new ConsumedLedgerEvent(
                UUID.randomUUID(),
                "TRANSACTION_POSTED",
                transactionId,
                "{\"transactionId\":\"%s\"}".formatted(transactionId),
                OffsetDateTime.now()
        ));

        int count = repository.countByTransactionIdAndEventType(
                transactionId,
                "TRANSACTION_POSTED"
        );

        assertThat(inserted).isTrue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void insertIfNotExistsIgnoresDuplicateEvent() {
        UUID transactionId = UUID.randomUUID();

        ConsumedLedgerEvent firstEvent = new ConsumedLedgerEvent(
                UUID.randomUUID(),
                "TRANSACTION_POSTED",
                transactionId,
                "{\"transactionId\":\"%s\"}".formatted(transactionId),
                OffsetDateTime.now()
        );

        ConsumedLedgerEvent duplicateEvent = new ConsumedLedgerEvent(
                UUID.randomUUID(),
                "TRANSACTION_POSTED",
                transactionId,
                "{\"transactionId\":\"%s\"}".formatted(transactionId),
                OffsetDateTime.now()
        );

        boolean firstInserted = repository.insertIfNotExists(firstEvent);
        boolean duplicateInserted = repository.insertIfNotExists(duplicateEvent);

        int count = repository.countByTransactionIdAndEventType(
                transactionId,
                "TRANSACTION_POSTED"
        );

        assertThat(firstInserted).isTrue();
        assertThat(duplicateInserted).isFalse();
        assertThat(count).isEqualTo(1);
    }
}