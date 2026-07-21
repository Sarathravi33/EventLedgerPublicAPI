package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;

/** Matches the Account Service's {@code POST /accounts/{accountId}/transactions} contract (§6.2). */
record AccountTransactionRequest(String eventId, String type, BigDecimal amount, Instant eventTimestamp) {
}
