package com.fanryan.ledgerflow.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanryan.ledgerflow.account.Account;
import com.fanryan.ledgerflow.account.AccountRepository;
import com.fanryan.ledgerflow.account.AccountStatus;
import com.fanryan.ledgerflow.outbox.OutboxPublisherService;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class TransactionOutboxIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void postedTransactionCreatesOutboxEventThatPublishesToKafka() throws Exception {
        UUID ownerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        OffsetDateTime now = OffsetDateTime.now();
        Account account = accountRepository.save(new Account(
                UUID.randomUUID(),
                ownerUserId,
                "USD",
                AccountStatus.ACTIVE,
                0,
                0,
                now,
                now
        ));

        TransactionResponse transaction = transactionService.submitTransaction(
                ownerUserId,
                "itx-outbox-" + UUID.randomUUID(),
                new CreateTransactionRequest(
                        account.id(),
                        TransactionType.DEPOSIT,
                        1000,
                        "USD",
                        "Integration outbox deposit"
                )
        );

        assertThat(transaction.status()).isEqualTo(TransactionStatus.POSTED);

        int published = outboxPublisherService.publishBatch();

        assertThat(published).isGreaterThanOrEqualTo(1);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaTemplate.getProducerFactory()
                                .getConfigurationProperties()
                                .get("bootstrap.servers"),
                        ConsumerConfig.GROUP_ID_CONFIG, "transaction-outbox-integration-test-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
                )
        )) {
            consumer.subscribe(List.of("ledger.events"));

            String consumedPayload = pollForPayloadContaining(
                    consumer,
                    transaction.id().toString()
            );

            assertThat(consumedPayload).contains(transaction.id().toString());
            assertThat(objectMapper.readTree(consumedPayload).get("status").asText())
                    .isEqualTo("POSTED");
        }
    }

    private String pollForPayloadContaining(
            KafkaConsumer<String, String> consumer,
            String expectedText
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

            for (var record : records.records("ledger.events")) {
                if (record.value().contains(expectedText)) {
                    return record.value();
                }
            }
        }

        throw new AssertionError("Did not consume ledger.events payload containing: " + expectedText);
    }
}
