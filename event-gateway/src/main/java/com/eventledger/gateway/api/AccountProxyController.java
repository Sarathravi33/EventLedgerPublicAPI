package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pass-through to the Account Service, added beyond the endpoint table in the assignment brief
 * (see IMPLEMENTATION_PLAN.md §6.1): the brief's graceful-degradation requirements explicitly
 * describe balance-query behavior for public clients when the Account Service is down, which
 * is only reachable/testable if the Gateway proxies it.
 */
@RestController
@RequestMapping("/accounts")
public class AccountProxyController {

    private final AccountServiceClient accountServiceClient;

    public AccountProxyController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return new BalanceResponse(accountId, accountServiceClient.getBalance(accountId));
    }
}
