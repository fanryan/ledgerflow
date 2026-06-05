package com.fanryan.ledgerflow.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:16-alpine")
    )
            .withDatabaseName("ledgerflow_test")
            .withUsername("ledgerflow")
            .withPassword("ledgerflow");

    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.8.0")
    );

    static {
        POSTGRES.start();
        KAFKA.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            KAFKA.stop();
            POSTGRES.stop();
        }));
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add("ledgerflow.outbox.publisher.enabled", () -> false);
        registry.add("spring.kafka.listener.auto-startup", () -> true);
    }
}
