package com.eventledger.account.api;

import com.eventledger.account.service.AccountService;
import com.eventledger.account.service.TransactionApplicationResult;
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
public class AccountController {

    private static final int RECENT_TRANSACTIONS_LIMIT = 20;

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(@PathVariable String accountId,
                                                                  @Valid @RequestBody TransactionRequest request) {
        TransactionApplicationResult result = accountService.applyTransaction(
                accountId, request.eventId(), request.type(), request.amount(), request.eventTimestamp());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TransactionResponse.from(result));
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return new BalanceResponse(accountId, accountService.getBalance(accountId));
    }

    @GetMapping("/{accountId}")
    public AccountDetailsResponse getAccountDetails(@PathVariable String accountId) {
        var account = accountService.getAccountOrThrow(accountId);
        var recentTransactions = accountService.getRecentTransactions(accountId, RECENT_TRANSACTIONS_LIMIT);
        return AccountDetailsResponse.from(account, recentTransactions);
    }
}
