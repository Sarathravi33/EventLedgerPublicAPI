package com.eventledger.account.api;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.service.AccountNotFoundException;
import com.eventledger.account.service.AccountService;
import com.eventledger.account.service.TransactionApplicationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AccountService accountService;

    private final Instant eventTimestamp = Instant.parse("2026-05-15T14:02:11Z");

    @Test
    void applyTransaction_newTransaction_returns201() throws Exception {
        Transaction transaction = new Transaction("evt-1", "acct-1", TransactionType.CREDIT,
                new BigDecimal("150.00"), eventTimestamp, Instant.now());
        when(accountService.applyTransaction(eq("acct-1"), eq("evt-1"), eq(TransactionType.CREDIT),
                any(BigDecimal.class), any(Instant.class)))
                .thenReturn(new TransactionApplicationResult("acct-1", new BigDecimal("150.00"), transaction, false));

        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType("application/json")
                        .content(requestJson("evt-1", "CREDIT", "150.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(150.00))
                .andExpect(jsonPath("$.transaction.eventId").value("evt-1"));
    }

    @Test
    void applyTransaction_idempotentReplay_returns200() throws Exception {
        Transaction transaction = new Transaction("evt-2", "acct-1", TransactionType.CREDIT,
                new BigDecimal("150.00"), eventTimestamp, Instant.now());
        when(accountService.applyTransaction(eq("acct-1"), eq("evt-2"), eq(TransactionType.CREDIT),
                any(BigDecimal.class), any(Instant.class)))
                .thenReturn(new TransactionApplicationResult("acct-1", new BigDecimal("150.00"), transaction, true));

        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType("application/json")
                        .content(requestJson("evt-2", "CREDIT", "150.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.eventId").value("evt-2"));
    }

    @Test
    void applyTransaction_missingEventId_returns400WithFieldError() throws Exception {
        String body = """
                {"type":"CREDIT","amount":10.00,"eventTimestamp":"2026-05-15T14:02:11Z"}
                """;

        mockMvc.perform(post("/accounts/acct-1/transactions").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors[?(@.field=='eventId')]").exists());
    }

    @Test
    void applyTransaction_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType("application/json")
                        .content(requestJson("evt-3", "CREDIT", "-5.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='amount')]").exists());
    }

    @Test
    void applyTransaction_unknownType_returns400() throws Exception {
        String body = """
                {"eventId":"evt-4","type":"FOO","amount":10.00,"eventTimestamp":"2026-05-15T14:02:11Z"}
                """;

        mockMvc.perform(post("/accounts/acct-1/transactions").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"));
    }

    @Test
    void getBalance_knownAccount_returns200() throws Exception {
        when(accountService.getBalance("acct-1")).thenReturn(new BigDecimal("42.00"));

        mockMvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(42.00));
    }

    @Test
    void getBalance_unknownAccount_returns404() throws Exception {
        when(accountService.getBalance("acct-missing")).thenThrow(new AccountNotFoundException("acct-missing"));

        mockMvc.perform(get("/accounts/acct-missing/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account Not Found"));
    }

    @Test
    void getAccountDetails_knownAccount_returns200WithTransactions() throws Exception {
        Account account = new Account("acct-1", Instant.now());
        account.setBalance(new BigDecimal("60.00"));
        Transaction transaction = new Transaction("evt-5", "acct-1", TransactionType.CREDIT,
                new BigDecimal("60.00"), eventTimestamp, Instant.now());
        when(accountService.getAccountOrThrow("acct-1")).thenReturn(account);
        when(accountService.getRecentTransactions(eq("acct-1"), anyInt())).thenReturn(List.of(transaction));

        mockMvc.perform(get("/accounts/acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(60.00))
                .andExpect(jsonPath("$.transactions[0].eventId").value("evt-5"));
    }

    private String requestJson(String eventId, String type, String amount) throws Exception {
        return objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("eventId", eventId);
            put("type", type);
            put("amount", new BigDecimal(amount));
            put("eventTimestamp", "2026-05-15T14:02:11Z");
        }});
    }
}
