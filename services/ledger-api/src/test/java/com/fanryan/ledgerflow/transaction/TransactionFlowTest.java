package com.fanryan.ledgerflow.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void submitTransactionRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "tx-no-auth")
                        .content("""
                                {
                                  "accountId": "00000000-0000-0000-0000-000000000001",
                                  "type": "DEPOSIT",
                                  "amountMinor": 1000,
                                  "currency": "USD",
                                  "description": "No auth"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void submitDepositCreatesPendingTransaction() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String idempotencyKey = "tx-create-pending-" + UUID.randomUUID();

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": 1000,
                                  "currency": "USD",
                                  "description": "Test deposit"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.ownerUserId").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amountMinor").value(1000))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void repeatedIdempotencyKeyReturnsSameTransaction() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String idempotencyKey = "tx-idempotent-repeat-" + UUID.randomUUID();

        String firstResponse = submitDeposit(
                accessToken,
                accountId,
                idempotencyKey
        );

        String secondResponse = submitDeposit(
                accessToken,
                accountId,
                idempotencyKey
        );

        String firstId = objectMapper.readTree(firstResponse).get("id").asText();
        String secondId = objectMapper.readTree(secondResponse).get("id").asText();

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void invalidAmountReturnsBadRequest() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "tx-invalid-amount")
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": 0,
                                  "currency": "USD",
                                  "description": "Invalid amount"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TRANSACTION_REQUEST"))
                .andExpect(jsonPath("$.message").value("Amount must be greater than zero"));
    }

    @Test
    void currencyMismatchReturnsBadRequest() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "tx-currency-mismatch")
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": 1000,
                                  "currency": "SGD",
                                  "description": "Wrong currency"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TRANSACTION_REQUEST"))
                .andExpect(jsonPath("$.message").value("Currency must match account currency"));
    }

    private String submitDeposit(
            String accessToken,
            String accountId,
            String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": 1000,
                                  "currency": "USD",
                                  "description": "Test deposit"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String createAccountAndGetId(String accessToken, String currency) throws Exception {
        String accountResponse = mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
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

        JsonNode accountJson = objectMapper.readTree(accountResponse);

        return accountJson.get("id").asText();
    }

    private String loginAndGetAccessToken() throws Exception {
        String loginResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
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

        JsonNode loginJson = objectMapper.readTree(loginResponse);

        return loginJson.get("accessToken").asText();
    }
}
