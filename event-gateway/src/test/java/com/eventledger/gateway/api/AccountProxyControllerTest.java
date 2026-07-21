package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountNotFoundException;
import com.eventledger.gateway.client.AccountServiceCallException;
import com.eventledger.gateway.client.AccountServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountProxyController.class)
class AccountProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @Test
    void getBalance_knownAccount_returns200() throws Exception {
        when(accountServiceClient.getBalance("acct-1")).thenReturn(new BigDecimal("42.00"));

        mockMvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(42.00));
    }

    @Test
    void getBalance_unknownAccount_returns404() throws Exception {
        when(accountServiceClient.getBalance("acct-missing")).thenThrow(new AccountNotFoundException("acct-missing"));

        mockMvc.perform(get("/accounts/acct-missing/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account Not Found"));
    }

    @Test
    void getBalance_accountServiceDown_returns503() throws Exception {
        when(accountServiceClient.getBalance("acct-1")).thenThrow(new AccountServiceCallException("connection refused"));

        mockMvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Account Service Unavailable"));
    }
}
