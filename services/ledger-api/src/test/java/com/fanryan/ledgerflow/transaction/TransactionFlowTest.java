package com.fanryan.ledgerflow.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fanryan.ledgerflow.ledger.LedgerEntry;
import com.fanryan.ledgerflow.ledger.LedgerEntryDirection;
import com.fanryan.ledgerflow.ledger.LedgerEntryRepository;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void submitTransactionRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
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
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitDepositCreatesPendingTransaction() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String idempotencyKey = "tx-create-pending-" + UUID.randomUUID();

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
                .andExpect(jsonPath("$.status").value("POSTED"));
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
    void repeatedIdempotencyKeyWithDifferentPayloadReturnsConflict() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String idempotencyKey = "tx-idempotent-conflict-" + UUID.randomUUID();

        submitDeposit(
                accessToken,
                accountId,
                idempotencyKey
        );

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": 2000,
                                  "currency": "USD",
                                  "description": "Different deposit"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Idempotency key was already used with a different request payload"));

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].balanceMinor".formatted(accountId)).value(1000));
    }

    @Test
    void invalidAmountReturnsBadRequest() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
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
                .andExpect(jsonPath("$.error_code").value("INVALID_TRANSACTION_REQUEST"))
                .andExpect(jsonPath("$.message").value("Amount must be greater than zero"));
    }

    @Test
    void currencyMismatchReturnsBadRequest() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
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
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("CURRENCY_MISMATCH"))
                .andExpect(jsonPath("$.message").value("Currency must match account currency"));
    }

    private String submitDeposit(
            String accessToken,
            String accountId,
            String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
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
    void depositUpdatesAccountBalance() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        submitDeposit(
                accessToken,
                accountId,
                "tx-deposit-balance-" + UUID.randomUUID()
        );

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].balanceMinor".formatted(accountId)).value(1000));
    }

    @Test
    void withdrawalUpdatesAccountBalance() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        submitDeposit(
                accessToken,
                accountId,
                "tx-withdraw-seed-" + UUID.randomUUID()
        );

        submitWithdrawal(
                accessToken,
                accountId,
                "tx-withdraw-" + UUID.randomUUID(),
                400
        );

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].balanceMinor".formatted(accountId)).value(600));
    }

    @Test
    void withdrawalWithInsufficientFundsReturnsConflict() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "tx-insufficient-" + UUID.randomUUID())
                        .content("""
                                {
                                "accountId": "%s",
                                "type": "WITHDRAWAL",
                                "amountMinor": 1000,
                                "currency": "USD",
                                "description": "Too much"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("Insufficient funds"));
    }

    @Test
    void insufficientFundsCreatesFailedTransaction() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String idempotencyKey = "tx-insufficient-failed-" + UUID.randomUUID();

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "WITHDRAWAL",
                                  "amountMinor": 1000,
                                  "currency": "USD",
                                  "description": "Too much"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("Insufficient funds"));

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.idempotencyKey == '%s')].status".formatted(idempotencyKey)).value("FAILED"))
                .andExpect(jsonPath("$[?(@.idempotencyKey == '%s')].type".formatted(idempotencyKey)).value("WITHDRAWAL"))
                .andExpect(jsonPath("$[?(@.idempotencyKey == '%s')].amountMinor".formatted(idempotencyKey)).value(1000));
    }

    @Test
    void repeatedIdempotencyKeyDoesNotUpdateBalanceTwice() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String idempotencyKey = "tx-idempotent-balance-" + UUID.randomUUID();

        submitDeposit(
                accessToken,
                accountId,
                idempotencyKey
        );

        submitDeposit(
                accessToken,
                accountId,
                idempotencyKey
        );

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].balanceMinor".formatted(accountId)).value(1000));
    }

    @Test
    void depositCreatesBalancedLedgerEntries() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        String response = submitDeposit(
                accessToken,
                accountId,
                "tx-balanced-deposit-" + UUID.randomUUID()
        );

        UUID transactionId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transactionId);

        assertThat(entries).hasSize(2);

        long totalDebits = entries.stream()
                .filter(entry -> entry.direction() == LedgerEntryDirection.DEBIT)
                .mapToLong(LedgerEntry::amountMinor)
                .sum();

        long totalCredits = entries.stream()
                .filter(entry -> entry.direction() == LedgerEntryDirection.CREDIT)
                .mapToLong(LedgerEntry::amountMinor)
                .sum();

        assertThat(totalDebits).isEqualTo(1000);
        assertThat(totalCredits).isEqualTo(1000);
    }

    @Test
    void withdrawalCreatesBalancedLedgerEntries() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        submitDeposit(
                accessToken,
                accountId,
                "tx-balanced-withdraw-seed-" + UUID.randomUUID()
        );

        String response = submitWithdrawal(
                accessToken,
                accountId,
                "tx-balanced-withdrawal-" + UUID.randomUUID(),
                400
        );

        UUID transactionId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transactionId);

        assertThat(entries).hasSize(2);

        long totalDebits = entries.stream()
                .filter(entry -> entry.direction() == LedgerEntryDirection.DEBIT)
                .mapToLong(LedgerEntry::amountMinor)
                .sum();

        long totalCredits = entries.stream()
                .filter(entry -> entry.direction() == LedgerEntryDirection.CREDIT)
                .mapToLong(LedgerEntry::amountMinor)
                .sum();

        assertThat(totalDebits).isEqualTo(400);
        assertThat(totalCredits).isEqualTo(400);
    }

    @Test
    void listTransactionsReturnsCurrentUsersTransactions() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String idempotencyKey = "tx-list-" + UUID.randomUUID();

        String transactionResponse = submitDeposit(
                accessToken,
                accountId,
                idempotencyKey
        );

        String transactionId = objectMapper
                .readTree(transactionResponse)
                .get("id")
                .asText();

        String listResponse = mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode transactions = objectMapper.readTree(listResponse);

        assertThat(transactions).isNotEmpty();

        boolean containsCreatedTransaction = false;

        for (JsonNode transaction : transactions) {
            if (transaction.get("id").asText().equals(transactionId)) {
                containsCreatedTransaction = true;
                break;
            }
        }

        assertThat(containsCreatedTransaction).isTrue();
    }

    private String submitWithdrawal(
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
                                "description": "Test withdrawal"
                                }
                                """.formatted(accountId, amountMinor)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
