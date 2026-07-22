package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Accounts (proxy)", description = "Pass-through balance lookups to the internal Account Service")
public class AccountProxyController {

    private final AccountServiceClient accountServiceClient;

    public AccountProxyController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get the current balance for an account",
            description = "Proxies to the Account Service through the same circuit-breaker/timeout/"
                    + "retry wrapping used for event submission. Returns 503 with a clear message if "
                    + "the Account Service is unreachable — never a fabricated or stale balance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance returned"),
            @ApiResponse(responseCode = "404", description = "Account has never received a transaction"),
            @ApiResponse(responseCode = "503", description = "Account Service unreachable")
    })
    public BalanceResponse getBalance(
            @Parameter(description = "Account to look up", example = "acct-123") @PathVariable String accountId) {
        return new BalanceResponse(accountId, accountServiceClient.getBalance(accountId));
    }
}
