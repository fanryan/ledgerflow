package com.fanryan.ledgerflow.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;

@AutoConfigureMockMvc
class TransactionConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void concurrentWithdrawalsDoNotOverdrawAccount() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        submitDeposit(
                accessToken,
                accountId,
                "tx-concurrency-seed-" + UUID.randomUUID(),
                1000
        );

        int workerCount = 2;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(workerCount)) {
            List<Future<Integer>> results = new ArrayList<>();

            for (int i = 0; i < workerCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return submitWithdrawalStatus(
                            accessToken,
                            accountId,
                            "tx-concurrency-withdraw-" + UUID.randomUUID(),
                            700
                    );
                }));
            }

            ready.await();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();

            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).contains(201);
            assertThat(statuses).contains(409);
        }

        long finalBalance = getAccountBalance(accessToken, accountId);

        assertThat(finalBalance).isEqualTo(300);
    }

    private int submitWithdrawalStatus(
            String accessToken,
            String accountId,
            String idempotencyKey,
            long amountMinor
    ) throws Exception {
        return mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "WITHDRAWAL",
                                  "amountMinor": %d,
                                  "currency": "USD",
                                  "description": "Concurrent withdrawal"
                                }
                                """.formatted(accountId, amountMinor)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void submitDeposit(
            String accessToken,
            String accountId,
            String idempotencyKey,
            long amountMinor
    ) throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": %d,
                                  "currency": "USD",
                                  "description": "Seed balance"
                                }
                                """.formatted(accountId, amountMinor)))
                .andExpect(status().isCreated());
    }

    private long getAccountBalance(String accessToken, String accountId) throws Exception {
        String accountResponse = mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode accounts = objectMapper.readTree(accountResponse);

        for (JsonNode account : accounts) {
            if (account.get("id").asText().equals(accountId)) {
                return account.get("balanceMinor").asLong();
            }
        }

        throw new AssertionError("Account not found in response");
    }

    private String createAccountAndGetId(String accessToken, String currency) throws Exception {
        String accountResponse = mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .content("""
                                {
                                  "currency": "%s"
                                }
                                """.formatted(currency)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(accountResponse).get("id").asText();
    }

    private String loginAndGetAccessToken() throws Exception {
        String loginResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("""
                                {
                                  "email": "admin@ledgerflow.local",
                                  "password": "password"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(loginResponse).get("accessToken").asText();
    }
}
