package com.fanryan.ledgerflow.account;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AccountFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createAccountRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("""
                                {
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createAccountWithValidTokenReturnsCreatedAccount() throws Exception {
        String accessToken = loginAndGetAccessToken();

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .content("""
                                {
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.ownerUserId").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void listAccountsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listAccountsReturnsAccountsForCurrentUser() throws Exception {
        String accessToken = loginAndGetAccessToken();

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .content("""
                                {
                                  "currency": "SGD"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ownerUserId").value("00000000-0000-0000-0000-000000000001"));
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

        JsonNode loginJson = objectMapper.readTree(loginResponse);

        return loginJson.get("accessToken").asText();
    }

    @Test
    void createAccountWithInvalidCurrencyReturnsBadRequest() throws Exception {
        String accessToken = loginAndGetAccessToken();

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .content("""
                                {
                                "currency": "USDD"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("INVALID_ACCOUNT_REQUEST"))
                .andExpect(jsonPath("$.message").value("Currency must be a 3-letter code"));
    }

    @Test
    void createAccountNormalizesCurrencyToUppercase() throws Exception {
        String accessToken = loginAndGetAccessToken();

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .content("""
                                {
                                "currency": "sgd"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("SGD"));
    }

    @Test
    void listLedgerEntriesRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/accounts/00000000-0000-0000-0000-000000000001/ledger-entries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listLedgerEntriesReturnsEntriesForAccount() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String idempotencyKey = "ledger-list-" + UUID.randomUUID();

        submitDeposit(accessToken, accountId, idempotencyKey);

        mockMvc.perform(get("/accounts/{accountId}/ledger-entries", accountId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].accountId").value(accountId))
                .andExpect(jsonPath("$[0].transactionId").isNotEmpty())
                .andExpect(jsonPath("$[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$[0].amountMinor").value(1000))
                .andExpect(jsonPath("$[0].currency").value("USD"));
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

        JsonNode accountJson = objectMapper.readTree(accountResponse);

        return accountJson.get("id").asText();
    }

    private void submitDeposit(
            String accessToken,
            String accountId,
            String idempotencyKey
    ) throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": 1000,
                                  "currency": "USD",
                                  "description": "Ledger entry test deposit"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isCreated());
    }
}