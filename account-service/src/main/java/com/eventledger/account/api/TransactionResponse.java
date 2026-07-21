package com.eventledger.account.api;

import com.eventledger.account.service.TransactionApplicationResult;

import java.math.BigDecimal;

public record TransactionResponse(String accountId, BigDecimal balance, TransactionView transaction) {

    public static TransactionResponse from(TransactionApplicationResult result) {
        return new TransactionResponse(result.accountId(), result.balance(), TransactionView.from(result.transaction()));
    }
}
