package com.fanryan.ledgerflow.reconciliation;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;

@AutoConfigureMockMvc
class ReconciliationFlowTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ledgerBalanceCheckRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/reconciliation/ledger-balance-check"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ledgerBalanceCheckCreatesReport() throws Exception {
        String accessToken = login();

        mockMvc.perform(post("/reconciliation/ledger-balance-check")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportType").value("LEDGER_BALANCE_CHECK"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.checkedTransactions").exists())
                .andExpect(jsonPath("$.imbalanceCount").exists())
                .andExpect(jsonPath("$.details").exists())
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void accountBalanceCheckRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/reconciliation/account-balance-check"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountBalanceCheckCreatesReport() throws Exception {
        String accessToken = login();

        mockMvc.perform(post("/reconciliation/account-balance-check")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportType").value("ACCOUNT_BALANCE_CHECK"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.checkedTransactions").exists())
                .andExpect(jsonPath("$.imbalanceCount").exists())
                .andExpect(jsonPath("$.details").exists())
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.completedAt").exists());
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
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

        return JsonPath.read(response, "$.accessToken");
    }
}
