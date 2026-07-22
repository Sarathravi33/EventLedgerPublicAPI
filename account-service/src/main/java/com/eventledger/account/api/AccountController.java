package com.eventledger.account.api;

import com.eventledger.account.service.AccountService;
import com.eventledger.account.service.TransactionApplicationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Account balances and transaction history (internal — called only by the Event Gateway)")
public class AccountController {

    private static final int RECENT_TRANSACTIONS_LIMIT = 20;

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/{accountId}/transactions")
    @Operation(summary = "Apply a transaction to an account",
            description = "Idempotent on eventId: replaying an already-applied eventId returns the "
                    + "original result unchanged (200) instead of re-applying it. A brand-new eventId "
                    + "is applied and returns 201. The account is created automatically on its first "
                    + "transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "New transaction applied"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of an already-applied eventId"),
            @ApiResponse(responseCode = "400", description = "Validation failed (missing field, non-positive amount, unknown type)")
    })
    public ResponseEntity<TransactionResponse> applyTransaction(
            @Parameter(description = "Account to credit/debit", example = "acct-123") @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest request) {
        TransactionApplicationResult result = accountService.applyTransaction(
                accountId, request.eventId(), request.type(), request.amount(), request.eventTimestamp());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TransactionResponse.from(result));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get the current balance for an account",
            description = "Balance is ΣCREDIT − ΣDEBIT, computed as an order-independent running total.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance returned"),
            @ApiResponse(responseCode = "404", description = "Account has never received a transaction")
    })
    public BalanceResponse getBalance(
            @Parameter(description = "Account to look up", example = "acct-123") @PathVariable String accountId) {
        return new BalanceResponse(accountId, accountService.getBalance(accountId));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get account details and recent transactions",
            description = "Returns the current balance plus the most recent transactions, newest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account details returned"),
            @ApiResponse(responseCode = "404", description = "Account has never received a transaction")
    })
    public AccountDetailsResponse getAccountDetails(
            @Parameter(description = "Account to look up", example = "acct-123") @PathVariable String accountId) {
        var account = accountService.getAccountOrThrow(accountId);
        var recentTransactions = accountService.getRecentTransactions(accountId, RECENT_TRANSACTIONS_LIMIT);
        return AccountDetailsResponse.from(account, recentTransactions);
    }
}
