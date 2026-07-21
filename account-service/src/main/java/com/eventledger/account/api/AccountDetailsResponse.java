package com.eventledger.account.api;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;

import java.math.BigDecimal;
import java.util.List;

public record AccountDetailsResponse(String accountId, BigDecimal balance, List<TransactionView> transactions) {

    public static AccountDetailsResponse from(Account account, List<Transaction> recentTransactions) {
        return new AccountDetailsResponse(account.getAccountId(), account.getBalance(),
                recentTransactions.stream().map(TransactionView::from).toList());
    }
}
