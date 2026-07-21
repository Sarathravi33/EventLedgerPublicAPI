package com.eventledger.account.service;

import com.eventledger.account.domain.Transaction;

import java.math.BigDecimal;

public record TransactionApplicationResult(String accountId, BigDecimal balance, Transaction transaction) {
}
