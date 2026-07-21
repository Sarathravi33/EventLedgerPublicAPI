package com.eventledger.gateway.client;

import com.eventledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Contract for the Gateway's outbound calls to the Account Service (§6.2 of
 * IMPLEMENTATION_PLAN.md).
 */
public interface AccountServiceClient {

    AccountServiceApplyResult applyTransaction(String accountId, String eventId, EventType type,
                                                BigDecimal amount, Instant eventTimestamp);

    BigDecimal getBalance(String accountId);
}
