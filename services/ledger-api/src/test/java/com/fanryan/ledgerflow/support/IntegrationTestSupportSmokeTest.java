package com.fanryan.ledgerflow.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class IntegrationTestSupportSmokeTest extends IntegrationTestSupport {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void contextStartsWithPostgresAndKafkaContainers() {
        assertThat(dataSource).isNotNull();
        assertThat(kafkaTemplate).isNotNull();
    }
}