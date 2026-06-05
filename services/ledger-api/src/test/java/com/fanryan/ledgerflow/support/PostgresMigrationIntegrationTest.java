package com.fanryan.ledgerflow.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PostgresMigrationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void flywayAppliesCoreLedgerSchemaToContainerizedPostgres() {
        Integer tableCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                              'users',
                              'accounts',
                              'transactions',
                              'ledger_entries',
                              'idempotency_keys',
                              'outbox_events',
                              'consumed_ledger_events',
                              'reconciliation_reports',
                              'dead_letter_events',
                              'flyway_schema_history'
                          )
                        """)
                .query(Integer.class)
                .single();

        assertThat(tableCount).isEqualTo(10);
    }
}