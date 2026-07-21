package com.eventledger.account.api;

import com.eventledger.account.domain.Transaction;
import com.eventledger.account.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionView(String eventId, TransactionType type, BigDecimal amount,
                               Instant eventTimestamp, Instant appliedAt) {

    public static TransactionView from(Transaction transaction) {
        return new TransactionView(transaction.getEventId(), transaction.getType(), transaction.getAmount(),
                transaction.getEventTimestamp(), transaction.getAppliedAt());
    }
}
