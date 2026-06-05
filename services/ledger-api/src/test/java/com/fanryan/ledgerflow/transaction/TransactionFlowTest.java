package com.fanryan.ledgerflow.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanryan.ledgerflow.account.Account;
import com.fanryan.ledgerflow.account.AccountRepository;
import com.fanryan.ledgerflow.account.AccountStatus;
import com.fanryan.ledgerflow.ledger.LedgerEntry;
import com.fanryan.ledgerflow.ledger.LedgerEntryDirection;
import com.fanryan.ledgerflow.ledger.LedgerEntryRepository;
import com.fanryan.ledgerflow.outbox.OutboxEvent;
import com.fanryan.ledgerflow.outbox.OutboxEventRepository;
import com.fanryan.ledgerflow.outbox.OutboxEventStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;

@AutoConfigureMockMvc
class TransactionFlowTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

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
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"))
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
                .andExpect(jsonPath("$.errorCode").value("INVALID_TRANSACTION_REQUEST"))
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
                .andExpect(jsonPath("$.errorCode").value("CURRENCY_MISMATCH"))
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
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
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
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("Insufficient funds"));

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.idempotencyKey == '%s')].status".formatted(idempotencyKey)).value("FAILED"))
                .andExpect(jsonPath("$[?(@.idempotencyKey == '%s')].type".formatted(idempotencyKey)).value("WITHDRAWAL"))
                .andExpect(jsonPath("$[?(@.idempotencyKey == '%s')].amountMinor".formatted(idempotencyKey)).value(1000));
    }

    @Test
    void frozenAccountCannotSubmitTransaction() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        updateAccountStatus(accountId, AccountStatus.FROZEN);

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "tx-frozen-" + UUID.randomUUID())
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": 1000,
                                  "currency": "USD",
                                  "description": "Frozen account deposit"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ACCOUNT_STATE"))
                .andExpect(jsonPath("$.message").value("Frozen accounts cannot submit transactions"));
    }

    @Test
    void closedAccountCannotSubmitTransaction() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        updateAccountStatus(accountId, AccountStatus.CLOSED);

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "tx-closed-" + UUID.randomUUID())
                        .content("""
                                {
                                  "accountId": "%s",
                                  "type": "DEPOSIT",
                                  "amountMinor": 1000,
                                  "currency": "USD",
                                  "description": "Closed account deposit"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ACCOUNT_STATE"))
                .andExpect(jsonPath("$.message").value("Closed accounts cannot submit transactions"));
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
    void postedTransactionCreatesPendingOutboxEvent() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        String response = submitDeposit(
                accessToken,
                accountId,
                "tx-outbox-posted-" + UUID.randomUUID()
        );

        UUID transactionId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(transactionId);

        assertThat(events).hasSize(1);

        OutboxEvent event = events.get(0);

        assertThat(event.aggregateType()).isEqualTo("TRANSACTION");
        assertThat(event.aggregateId()).isEqualTo(transactionId);
        assertThat(event.eventType()).isEqualTo("TRANSACTION_POSTED");
        assertThat(event.status()).isEqualTo(OutboxEventStatus.PENDING);

        JsonNode payload = objectMapper.readTree(event.payload());

        assertThat(payload.get("transactionId").asText()).isEqualTo(transactionId.toString());
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

    @Test
    void reverseDepositCreatesOffsettingTransactionAndRestoresBalance() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        String depositResponse = submitDeposit(
                accessToken,
                accountId,
                "tx-reverse-deposit-original-" + UUID.randomUUID()
        );

        String originalTransactionId = objectMapper
                .readTree(depositResponse)
                .get("id")
                .asText();

        mockMvc.perform(post("/transactions/{transactionId}/reverse", originalTransactionId)
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "tx-reverse-deposit-" + UUID.randomUUID())
                        .content("""
                                {
                                  "reason": "Customer requested reversal"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.amountMinor").value(1000))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.reversalOfTransactionId").value(originalTransactionId));

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].balanceMinor".formatted(accountId)).value(0));
    }

    @Test
    void cannotReverseSameTransactionTwice() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        String depositResponse = submitDeposit(
                accessToken,
                accountId,
                "tx-double-reverse-original-" + UUID.randomUUID()
        );

        String originalTransactionId = objectMapper
                .readTree(depositResponse)
                .get("id")
                .asText();

        reverseTransaction(
                accessToken,
                originalTransactionId,
                "tx-double-reverse-first-" + UUID.randomUUID()
        );

        mockMvc.perform(post("/transactions/{transactionId}/reverse", originalTransactionId)
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "tx-double-reverse-second-" + UUID.randomUUID())
                        .content("""
                                {
                                  "reason": "Duplicate reversal attempt"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REVERSAL_REQUEST"))
                .andExpect(jsonPath("$.message").value("Transaction has already been reversed"));
    }

    @Test
    void reversalRequiresReason() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");

        String depositResponse = submitDeposit(
                accessToken,
                accountId,
                "tx-reverse-no-reason-original-" + UUID.randomUUID()
        );

        String originalTransactionId = objectMapper
                .readTree(depositResponse)
                .get("id")
                .asText();

        mockMvc.perform(post("/transactions/{transactionId}/reverse", originalTransactionId)
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "tx-reverse-no-reason-" + UUID.randomUUID())
                        .content("""
                                {
                                  "reason": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REVERSAL_REQUEST"))
                .andExpect(jsonPath("$.message").value("Reversal reason is required"));
    }

    @Test
    void repeatedReversalIdempotencyKeyReturnsSameReversalTransaction() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String reversalIdempotencyKey = "tx-reverse-repeat-" + UUID.randomUUID();

        String depositResponse = submitDeposit(
                accessToken,
                accountId,
                "tx-reverse-repeat-original-" + UUID.randomUUID()
        );

        String originalTransactionId = objectMapper
                .readTree(depositResponse)
                .get("id")
                .asText();

        String firstReversalResponse = reverseTransaction(
                accessToken,
                originalTransactionId,
                reversalIdempotencyKey
        );

        String secondReversalResponse = reverseTransaction(
                accessToken,
                originalTransactionId,
                reversalIdempotencyKey
        );

        String firstReversalId = objectMapper
                .readTree(firstReversalResponse)
                .get("id")
                .asText();

        String secondReversalId = objectMapper
                .readTree(secondReversalResponse)
                .get("id")
                .asText();

        assertThat(secondReversalId).isEqualTo(firstReversalId);

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].balanceMinor".formatted(accountId)).value(0));
    }

    @Test
    void repeatedReversalIdempotencyKeyWithDifferentPayloadReturnsConflict() throws Exception {
        String accessToken = loginAndGetAccessToken();
        String accountId = createAccountAndGetId(accessToken, "USD");
        String reversalIdempotencyKey = "tx-reverse-conflict-" + UUID.randomUUID();

        String depositResponse = submitDeposit(
                accessToken,
                accountId,
                "tx-reverse-conflict-original-" + UUID.randomUUID()
        );

        String originalTransactionId = objectMapper
                .readTree(depositResponse)
                .get("id")
                .asText();

        reverseTransaction(
                accessToken,
                originalTransactionId,
                reversalIdempotencyKey
        );

        mockMvc.perform(post("/transactions/{transactionId}/reverse", originalTransactionId)
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", reversalIdempotencyKey)
                        .content("""
                                {
                                  "reason": "Different reversal reason"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Idempotency key was already used with a different request payload"));
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

    private String reverseTransaction(
            String accessToken,
            String transactionId,
            String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post("/transactions/{transactionId}/reverse", transactionId)
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("""
                                {
                                  "reason": "Test reversal"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void updateAccountStatus(String accountId, AccountStatus status) {
        Account account = accountRepository.findById(UUID.fromString(accountId))
                .orElseThrow();

        OffsetDateTime now = OffsetDateTime.now();

        Account updatedAccount = new Account(
                account.id(),
                account.ownerUserId(),
                account.currency(),
                status,
                account.balanceMinor(),
                account.version(),
                account.createdAt(),
                now
        );

        accountRepository.save(updatedAccount);
    }
}
