package com.fanryan.ledgerflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fanryan.ledgerflow.support.IntegrationTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxPublisherKafkaIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishBatchPublishesPendingOutboxEventToKafka() {
        UUID transactionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String payload = """
                {
                  "transactionId": "%s",
                  "type": "DEPOSIT",
                  "status": "POSTED",
                  "amountMinor": 1000,
                  "currency": "USD"
                }
                """.formatted(transactionId);

        outboxEventRepository.save(
                new OutboxEvent(
                        UUID.randomUUID(),
                        "TRANSACTION",
                        transactionId,
                        "TRANSACTION_POSTED",
                        payload,
                        OutboxEventStatus.PENDING,
                        0,
                        now,
                        null,
                        null,
                        null,
                        null,
                        now,
                        now
                )
        );

        int published = outboxPublisherService.publishBatch();

        assertThat(published).isEqualTo(1);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaTemplate.getProducerFactory()
                                .getConfigurationProperties()
                                .get("bootstrap.servers"),
                        ConsumerConfig.GROUP_ID_CONFIG, "outbox-publisher-kafka-integration-test-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
                )
        )) {
            consumer.subscribe(List.of("ledger.events"));

            String consumedPayload = pollForPayloadContaining(
                    consumer,
                    transactionId.toString()
            );

            assertThat(consumedPayload).contains(transactionId.toString());
            assertThat(consumedPayload).contains("\"status\": \"POSTED\"");
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
